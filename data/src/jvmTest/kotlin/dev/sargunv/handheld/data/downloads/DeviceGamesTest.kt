package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path

class DeviceGamesTest {
  private val dir = createTempDirectory("device-games-test").toFile()
  private val storage = File(dir, "storage").apply { mkdirs() }
  private val library = LibraryCache.open(path = null)

  @AfterTest
  fun cleanUp() {
    library.close()
    dir.deleteRecursively()
  }

  private val game = Random(1).nextBytes(300_000)
  private val disc1 = Random(2).nextBytes(100_000)
  private val disc2 = Random(3).nextBytes(120_000)

  private fun hash(algorithm: String, bytes: ByteArray) =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

  private fun Rom(id: Long, platformId: Long, fileName: String, files: List<RomFile>) =
    Rom(
      id = id,
      platformId = platformId,
      platformSlug = if (platformId == 1L) "gba" else "psx",
      title = "Game $id",
      sortKey = "game $id",
      alternativeNames = emptyList(),
      fileName = fileName,
      sizeBytes = files.sumOf { it.sizeBytes },
      summary = null,
      genres = emptyList(),
      regions = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      hasMultipleFiles = files.size > 1,
      files = files,
      updatedAt = Instant.fromEpochSeconds(0),
      createdAt = Instant.fromEpochSeconds(0),
    )

  private val single = RomFile(11, "Game (USA).gba", game.size.toLong(), null)
  private val gameRom = Rom(1, 1, single.fileName, listOf(single))
  private val sagaRom =
    Rom(
      2,
      2,
      "Saga (USA)",
      listOf(
        RomFile(22, "Saga (USA) (Disc 2).chd", disc2.size.toLong(), null),
        RomFile(21, "Saga (USA) (Disc 1).chd", disc1.size.toLong(), null),
      ),
    )

  private suspend fun fillLibrary() {
    library.replacePlatforms(
      listOf(
        Platform(1, "gba", "gba", "Game Boy Advance", 1),
        Platform(2, "psx", "psx", "PlayStation", 1),
      ),
    )
  }

  private fun games(scope: CoroutineScope) =
    DeviceGames(
      library = library,
      storage = { listOf(StorageTarget(storage.path, "Test storage")) },
      scope = scope,
      contentCacheFile = Path(File(dir, "local-content.json").path),
    )

  private fun file(path: String) = File(storage, path)

  /** Runs [block] with a timeout in real time, since the scans run on real threads. */
  private suspend fun <T> waitFor(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000, block) }

  @Test
  fun findsAGameCopiedUnderAnotherName() = runTest {
    fillLibrary()
    val file = gameRom.files.single()
    val rom =
      gameRom.copy(
        files =
          listOf(
            file.copy(
              crcHash = CRC32().apply { update(game) }.value.toString(16).padStart(8, '0'),
              sha1Hash = hash("SHA-1", game),
            ),
          ),
      )
    library.upsertRoms(listOf(rom))
    val copied =
      file("ROMs/gba/my copy.gba").apply {
        parentFile.mkdirs()
        writeBytes(game)
      }
    val games = games(backgroundScope)
    waitFor { games.onDevice.first { it[rom.id] == copied.path } }

    // A file replaced since the scan isn't the game's any more, so it stays.
    val modified = copied.lastModified()
    copied.writeBytes(disc1)
    copied.setLastModified(modified + 10_000)
    assertEquals(Removal.NotOnDevice, games.remove(rom))
    assertContentEquals(disc1, copied.readBytes())
    copied.writeBytes(game)
    games.rescan()
    waitFor { games.onDevice.first { it[rom.id] == copied.path } }

    assertEquals(Removal.Removed, games.remove(rom))
    assertFalse(copied.exists())
    assertEquals(null, games.onDevice.first()[rom.id])
  }

  // Of two server games on one file, the file's page and the device's games pick the same one.
  @Test
  fun aFileOfTwoServerGamesIsTheLowerIds() = runTest {
    fillLibrary()
    val file = gameRom.files.single()
    val hashed =
      file.copy(
        crcHash = CRC32().apply { update(game) }.value.toString(16).padStart(8, '0'),
        sha1Hash = hash("SHA-1", game),
      )
    val first =
      gameRom.copy(id = 21, fileName = "A.gba", files = listOf(hashed.copy(fileName = "A.gba")))
    val second =
      gameRom.copy(
        id = 22,
        fileName = "B.gba",
        files = listOf(hashed.copy(id = hashed.id + 1, fileName = "B.gba")),
      )
    library.upsertRoms(listOf(second, first))
    val copied =
      file("ROMs/gba/my copy.gba").apply {
        parentFile.mkdirs()
        writeBytes(game)
      }
    val games = games(backgroundScope)
    // Both are on the device, on the one file.
    waitFor { games.onDevice.first { it.keys == setOf(21L, 22L) } }
    assertEquals(
      LocalGameState.Matched(21),
      waitFor { games.localGame(copied.path).first { it is LocalGameState.Matched } },
    )
  }

  @Test
  fun aFileThatMatchesNothingIsALocalGameUntilRemoved() = runTest {
    fillLibrary()
    val homebrew =
      file("ROMs/gba/Homebrew (PD).gba").apply {
        parentFile.mkdirs()
        writeBytes(disc1)
      }
    // A save beside it isn't a game.
    file("ROMs/gba/Homebrew (PD).srm").writeBytes(ByteArray(8))
    val games = games(backgroundScope)

    val local = waitFor { games.localGames().first { it.isNotEmpty() } }.single()
    assertEquals(homebrew.path, local.path)
    assertEquals("Homebrew (PD)", local.title)
    assertEquals("gba", local.platformSlug)
    assertEquals(1L, local.platformId)
    assertEquals(disc1.size.toLong(), local.sizeBytes)
    assertEquals(
      LocalGameState.Local(local),
      waitFor { games.localGame(local.path).first { it != null } },
    )

    assertEquals(Removal.Removed, games.remove(local))
    assertFalse(homebrew.exists())
    assertTrue(file("ROMs/gba/Homebrew (PD).srm").exists())
    waitFor { games.localGame(local.path).first { it == LocalGameState.Gone } }
    waitFor { games.localGames().first { it.isEmpty() } }
  }

  @Test
  fun removingALocalGameDeletesOnlyAFileTheScanFound() = runTest {
    fillLibrary()
    file("ROMs/gba/Homebrew.gba").apply {
      parentFile.mkdirs()
      writeBytes(disc1)
    }
    val outside = File(dir, "elsewhere.gba").apply { writeBytes(disc1) }
    val games = games(backgroundScope)
    val local = waitFor { games.localGames().first { it.isNotEmpty() } }.single()

    assertEquals(Removal.NotOnDevice, games.remove(local.copy(path = outside.path)))
    assertTrue(outside.exists())
    // A file of another size since the scan isn't the one it found.
    file("ROMs/gba/Homebrew.gba").writeBytes(disc2)
    assertEquals(Removal.NotOnDevice, games.remove(local))
    assertTrue(file("ROMs/gba/Homebrew.gba").exists())
  }

  @Test
  fun aLocalGameBecomesTheRomItMatchesOnceTheLibraryHasIt() = runTest {
    fillLibrary()
    val copied =
      file("ROMs/gba/my copy.gba").apply {
        parentFile.mkdirs()
        writeBytes(game)
      }
    val games = games(backgroundScope)
    val local = waitFor { games.localGames().first { it.isNotEmpty() } }.single()
    assertEquals(copied.path, local.path)

    // The server gains the game, such as after the file is uploaded.
    val file = gameRom.files.single()
    val rom =
      gameRom.copy(
        files =
          listOf(
            file.copy(crcHash = CRC32().apply { update(game) }.value.toString(16).padStart(8, '0')),
          ),
      )
    library.upsertRoms(listOf(rom))
    assertEquals(
      LocalGameState.Matched(rom.id),
      waitFor { games.localGame(copied.path).first { it is LocalGameState.Matched } },
    )
    waitFor { games.localGames().first { it.isEmpty() } }
    waitFor { games.onDevice.first { it[rom.id] == copied.path } }
  }

  @Test
  fun aLocalGameNamedAsTheServerNamesAGameBecomesIt() = runTest {
    fillLibrary()
    val named =
      file("ROMs/gba/${single.fileName}").apply {
        parentFile.mkdirs()
        writeBytes(disc1)
      }
    val games = games(backgroundScope)
    // With no game of that name, the file is local.
    waitFor { games.localGames().first { it.isNotEmpty() } }
    library.upsertRoms(listOf(gameRom))
    assertEquals(
      LocalGameState.Matched(gameRom.id),
      waitFor { games.localGame(named.path).first { it is LocalGameState.Matched } },
    )
  }

  @Test
  fun aDiscSetCopiedInLooseIsOneLocalGame() = runTest {
    fillLibrary()
    val psx = file("ROMs/psx").apply { mkdirs() }
    val cue =
      File(psx, "Demo.cue").apply {
        writeText("FILE \"Demo (Track 1).bin\" BINARY\nFILE \"demo (track 2).bin\" BINARY\n")
      }
    val tracks =
      listOf(File(psx, "Demo (Track 1).bin"), File(psx, "Demo (Track 2).bin")).onEach {
        it.writeBytes(disc1)
      }
    // A playlist's discs, and the tracks their cue sheets list, are its set.
    val m3u =
      File(psx, "Trilogy.m3u").apply { writeText("Trilogy (Disc 1).cue\nTrilogy (Disc 2).cue\n") }
    val discs =
      (1..2).flatMap { n ->
        listOf(
          File(psx, "Trilogy (Disc $n).cue").apply {
            writeText("FILE \"Trilogy (Disc $n).bin\" BINARY\n")
          },
          File(psx, "Trilogy (Disc $n).bin").apply { writeBytes(disc2) },
        )
      }
    // A file no descriptor lists is a game of its own.
    val stray = File(psx, "Stray.bin").apply { writeBytes(disc1) }
    val games = games(backgroundScope)

    val local = waitFor { games.localGames().first { it.isNotEmpty() } }.sortedBy { it.path }
    assertEquals(listOf(cue.path, stray.path, m3u.path), local.map { it.path })
    assertEquals(tracks.map { it.path }, local[0].otherFiles)
    assertEquals(cue.length() + 2 * disc1.size, local[0].sizeBytes)
    assertEquals("Demo", local[0].title)
    assertEquals(discs.map { it.path }.sorted(), local[2].otherFiles.sorted())
    // The tracks of a set aren't read, however big they are.
    val cached = File(dir, "local-content.json").readText()
    assertTrue(stray.path in cached)
    for (part in tracks + discs.filter { it.extension == "bin" }) {
      assertFalse(part.path in cached, part.name)
    }
  }

  @Test
  fun aSetWithAFileThatsAGameOfTheLibraryIsItsFilesEachOnItsOwn() = runTest {
    fillLibrary()
    val file = gameRom.files.single()
    val rom =
      gameRom.copy(
        files =
          listOf(
            file.copy(crcHash = CRC32().apply { update(game) }.value.toString(16).padStart(8, '0')),
          ),
      )
    library.upsertRoms(listOf(rom))
    val gba = file("ROMs/gba").apply { mkdirs() }
    val playlist = File(gba, "Pack.m3u").apply { writeText("copy.gba\nother.gba\n") }
    // Of a game's size, so the scan reads it.
    val copy = File(gba, "copy.gba").apply { writeBytes(game) }
    val other = File(gba, "other.gba").apply { writeBytes(disc1) }
    val games = games(backgroundScope)

    waitFor { games.onDevice.first { it[rom.id] == copy.path } }
    val local = waitFor { games.localGames().first { it.size == 2 } }.sortedBy { it.path }
    assertEquals(listOf(playlist.path, other.path), local.map { it.path })
    assertEquals(emptyList(), local[0].otherFiles)
  }

  @Test
  fun removingADiscSetDeletesEveryFileItListsUnlessOneChanged() = runTest {
    fillLibrary()
    val psx = file("ROMs/psx").apply { mkdirs() }
    val cue = File(psx, "Demo.cue").apply { writeText("FILE \"Demo.bin\" BINARY\n") }
    val track = File(psx, "Demo.bin").apply { writeBytes(disc1) }
    val other = File(psx, "Other.bin").apply { writeBytes(disc1) }
    val games = games(backgroundScope)
    suspend fun scanned(sized: (Long) -> Boolean) = waitFor {
      games.localGames().first { games -> games.any { it.path == cue.path && sized(it.sizeBytes) } }
    }
      .single { it.path == cue.path }
    val local = scanned { true }

    // A track that changed since the scan isn't the one it found.
    track.writeBytes(disc2)
    assertEquals(Removal.NotOnDevice, games.remove(local))
    assertTrue(cue.exists() && track.exists())
    // The removal's rescan finds the changed track; once it's back, the next one finds the set.
    scanned { it != local.sizeBytes }
    track.writeBytes(disc1)
    games.rescan()
    assertEquals(Removal.Removed, games.remove(scanned { it == local.sizeBytes }))
    assertFalse(cue.exists())
    assertFalse(track.exists())
    assertTrue(other.exists())
  }

  // Two sheets that list one track: removing one game leaves the track for the other.
  @Test
  fun removingADiscSetKeepsATrackAnotherOneLists() = runTest {
    fillLibrary()
    val psx = file("ROMs/psx").apply { mkdirs() }
    val a = File(psx, "A.cue").apply { writeText("FILE \"Shared.bin\" BINARY\n") }
    val b = File(psx, "B.cue").apply { writeText("FILE \"Shared.bin\" BINARY\n") }
    val track = File(psx, "Shared.bin").apply { writeBytes(disc1) }
    val games = games(backgroundScope)
    val local = waitFor {
      games.localGames().first { local -> local.count { it.path.endsWith(".cue") } == 2 }
    }
    assertEquals(Removal.Removed, games.remove(local.single { it.path == a.path }))
    assertFalse(a.exists())
    assertTrue(track.exists(), "B's track stays")
    assertTrue(b.exists())
  }

  @Test
  fun onlyFilesThatMayBeGamesAreLocalGames() {
    for (name in listOf("a.gba", "b.zip", "c.chd", "d.m3u", "e.cue", "f", "Sonic.md")) {
      assertTrue(isGameFile("/ROMs/gba/$name"), name)
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
      )) {
      assertFalse(isGameFile("/ROMs/gba/$name"), name)
    }
  }

  @Test
  fun removesOnlyTheGamesOwnFiles() = runTest {
    fillLibrary()
    library.upsertRoms(listOf(gameRom, sagaRom))
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
    val gbaSave = file("ROMs/gba/Game (USA).sav").apply { writeText("save") }
    val folder = file("ROMs/psx/Saga (USA)").apply { mkdirs() }
    for (name in listOf("Saga (USA) (Disc 1).chd", "Saga (USA) (Disc 2).chd", "Saga (USA).m3u")) {
      File(folder, name).writeText(name)
    }
    val discSave = File(folder, "saves/Saga (USA).srm").apply { parentFile.mkdirs() }
    discSave.writeText("save")
    val games = games(backgroundScope)
    waitFor { games.onDevice.first { it.size == 2 } }

    assertEquals(Removal.Removed, games.remove(gameRom))
    assertFalse(gba.exists())
    assertTrue(gbaSave.exists())
    assertEquals(setOf(2L), games.onDevice.first().keys)

    // A folder that holds something besides the game stays whole.
    assertEquals(Removal.HoldsOtherFiles(listOf(discSave.name)), games.remove(sagaRom))
    assertEquals(4, folder.walk().count { it.isFile })
    discSave.delete()
    // So does one with a file that only shares a name with one of the game's.
    val stray = File(folder, "extras/Saga (USA) (Disc 1).chd").apply { parentFile.mkdirs() }
    stray.writeText("not the game")
    assertEquals(Removal.HoldsOtherFiles(listOf(stray.name)), games.remove(sagaRom))
    assertTrue(stray.exists())
    stray.delete()
    assertEquals(Removal.Removed, games.remove(sagaRom))
    assertFalse(folder.exists())
    assertEquals(emptyMap(), games.onDevice.first())
    assertEquals(Removal.NotOnDevice, games.remove(sagaRom))
  }

  @Test
  fun saysSoWhenAGameCantBeRemoved() = runTest {
    fillLibrary()
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
    val games = games(backgroundScope)
    // A folder the app can't write to, as on a read-only card.
    gba.parentFile.setWritable(false)
    try {
      assertIs<Removal.Failed>(games.remove(gameRom))
      assertTrue(gba.exists())
    } finally {
      gba.parentFile.setWritable(true)
    }
  }
}
