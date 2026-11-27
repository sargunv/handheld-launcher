package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class LocalContentTest {
  private val dir = createTempDirectory("local-content-test").toFile()
  private val storage = File(dir, "storage")
  private val gba = File(storage, "ROMs/gba").apply { mkdirs() }
  private val cacheFile = Path(File(dir, "local-content.json").path)
  private val targets = listOf(StorageTarget(storage.path, "Test storage"))
  private val platforms = listOf(Platform(1, "gba", "gba", "Game Boy Advance", 2))

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private val game = Random(1).nextBytes(50_000)
  private val other = Random(2).nextBytes(40_000)

  private fun crc(bytes: ByteArray) =
    CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')

  private fun sha1(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun zip(file: File, vararg members: Pair<String, ByteArray>) =
    ZipOutputStream(file.outputStream()).use { out ->
      for ((name, bytes) in members) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
      }
    }

  /** A one-file ROM on the GBA, whose file RomM hashed as [content]. */
  private fun rom(
    id: Long,
    fileName: String,
    content: ByteArray,
    sizeBytes: Long = content.size.toLong(),
    sha1: String? = sha1(content),
  ) =
    Rom(
      id = id,
      platformId = 1,
      platformSlug = "gba",
      title = "Game $id",
      sortKey = "game $id",
      alternativeNames = emptyList(),
      fileName = fileName,
      sizeBytes = sizeBytes,
      summary = null,
      genres = emptyList(),
      regions = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      hasMultipleFiles = false,
      files = listOf(RomFile(id * 10, fileName, sizeBytes, null, crc(content), null, sha1)),
      updatedAt = Instant.fromEpochSeconds(0),
      createdAt = Instant.fromEpochSeconds(0),
    )

  @Test
  fun readsAZipsCrcFromItsDirectory() {
    val file = File(dir, "game.zip")
    zip(file, "Game.gba" to game)
    assertEquals(crc(game), oneFileZipCrc(Path(file.path)))
    zip(file, "Game.gba" to game, "Other.gba" to other)
    assertNull(oneFileZipCrc(Path(file.path)))
    assertNull(oneFileZipCrc(Path(file.apply { writeBytes(game) }.path)))
  }

  @Test
  fun hashesAWholeFile() {
    val file = File(dir, "game.gba").apply { writeBytes(game) }
    assertEquals(crc(game) to sha1(game), crcAndSha1(Path(file.path)))
  }

  @Test
  fun readsTheSha1InAChdHeader() {
    // The header of Jak and Daxter - The Precursor Legacy.chd, whose SHA-1 RomM lists for it.
    val header =
      listOf(
          "4d436f6d707248440000007c00000005",
          "6c7a6d617a6c696268756666666c6163",
          "00000000570a80000000000033a9a245",
          "000000000000007c0000100000000800",
          "e695d5b98d598bfc0262ec73ba1baf36",
          "8a81039b58dc525550a5a7050cb9940d",
          "5d5bfaee0f2fce880000000000000000",
          "000000000000000000000000",
        )
        .joinToString("")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    assertEquals(124, header.size)
    assertEquals("58dc525550a5a7050cb9940d5d5bfaee0f2fce88", chdHeaderSha1(header))
    // Not a CHD of version 5.
    assertNull(chdHeaderSha1(header.copyOf().also { it[15] = 4 }))
    assertNull(chdHeaderSha1(header.copyOf().also { it[0] = 0 }))
    assertNull(chdHeaderSha1(header.copyOf(100)))
  }

  @Test
  fun matchesGamesUnderOtherNamesAndPackaging() = runTest {
    // The player unzipped one game, zipped another, and renamed both.
    File(gba, "my game.gba").writeBytes(game)
    zip(File(gba, "other.zip"), "Other (USA).gba" to other)
    File(gba, "notes.txt").writeBytes("not a game".encodeToByteArray())
    val zipped = rom(1, "Game (USA).zip", game, sizeBytes = 12_345)
    val plain = rom(2, "Other (USA).gba", other)

    val scan = ContentScanner(cacheFile).scan(targets, platforms, listOf(zipped, plain))

    assertEquals(
      mapOf(1L to File(gba, "my game.gba").path, 2L to File(gba, "other.zip").path),
      scan.matched,
    )
    // Notes aren't games, so they aren't read or listed.
    assertEquals(emptyList(), scan.unmatched)
  }

  @Test
  fun onlyFilesThatMayBeGamesAreScanned() {
    for (name in listOf("a.gba", "b.zip", "c.chd", "d.m3u", "e.cue", "f", "Sonic.md")) {
      assertTrue(isGameFile(name), name)
    }
    for (name in
      listOf(
        "a.srm",
        "b.sav",
        "c.state",
        "d.state3",
        "e.st0",
        "f.png",
        "g.txt",
        "h.ips",
        "i.tmp",
        "j.gba.part",
        ".k.gba",
      )) {
      assertFalse(isGameFile(name), name)
    }
  }

  @Test
  fun leavesGamesOnTheDeviceByNameAlone() = runTest {
    File(gba, "Game (USA).gba").writeBytes(game)
    File(gba, "copy.gba").writeBytes(game)
    val scan =
      ContentScanner(cacheFile).scan(targets, platforms, listOf(rom(1, "Game (USA).gba", game)))
    // The game is on the device by its own name, so the copy is a file of its own.
    assertEquals(emptyMap(), scan.matched)
    assertEquals(listOf(File(gba, "copy.gba").path), scan.unmatched.map { it.path })
  }

  @Test
  fun aSha1ThatDiffersOverridesTheCrc() = runTest {
    File(gba, "my game.gba").writeBytes(game)
    val scan =
      ContentScanner(cacheFile)
        .scan(targets, platforms, listOf(rom(1, "Game (USA).gba", game, sha1 = sha1(other))))
    assertEquals(emptyMap(), scan.matched)
  }

  @Test
  fun readsBigFilesOnlyWhenTheirSizeIsAGames() = runTest {
    File(gba, "big.gba").writeBytes(game)
    val big = File(gba, "other.iso").apply { writeBytes(other) }
    // Both files are bigger than the limit, and only the first's size is a game's, so the other
    // isn't read and doesn't match the game whose content it has.
    val roms = listOf(rom(1, "Game (USA).gba", game), rom(2, "Other (USA).iso", other, 12_345))
    val scan = ContentScanner(cacheFile, wholeFileLimit = 1_000).scan(targets, platforms, roms)

    assertEquals(mapOf(1L to File(gba, "big.gba").path), scan.matched)
    assertEquals(listOf(big.path), scan.unmatched.map { it.path })
  }

  @Test
  fun readsAFileAgainOnlyWhenItChanges() = runTest {
    val file = File(gba, "my game.gba").apply { writeBytes(game) }
    val roms = listOf(rom(1, "Game (USA).gba", game))
    assertEquals(setOf(1L), ContentScanner(cacheFile).scan(targets, platforms, roms).matched.keys)

    // The content changes, keeping its size and time, so a new scanner trusts the cache.
    val modified = file.lastModified()
    file.writeBytes(other.copyOf(game.size))
    file.setLastModified(modified)
    assertEquals(setOf(1L), ContentScanner(cacheFile).scan(targets, platforms, roms).matched.keys)

    // Once the time changes too, it reads the file again.
    file.setLastModified(modified - 10_000)
    assertEquals(emptySet(), ContentScanner(cacheFile).scan(targets, platforms, roms).matched.keys)
  }

  // A removal deletes what it checks, so it reads the file, not the cache.
  @Test
  fun aRemovalChecksTheFileItselfNotTheCache() = runTest {
    val file = File(gba, "my game.gba").apply { writeBytes(game) }
    val rom = rom(1, "Game (USA).gba", game)
    val scanner = ContentScanner(cacheFile)
    assertEquals(setOf(1L), scanner.scan(targets, platforms, listOf(rom)).matched.keys)
    val modified = file.lastModified()
    file.writeBytes(other.copyOf(game.size))
    file.setLastModified(modified)
    assertEquals(false, scanner.stillMatches(Path(file.path), rom))
  }
}
