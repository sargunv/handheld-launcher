package dev.sargunv.handheld.data.downloads

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.io.files.Path

class FileChecksTest {
  private val dir = createTempDirectory("file-checks-test").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun md5(vararg parts: ByteArray) =
    MessageDigest.getInstance("MD5")
      .apply { parts.forEach(::update) }
      .digest()
      .joinToString("") { "%02x".format(it) }

  private fun zip(name: String, members: Map<String, ByteArray>): File {
    val file = File(dir, name)
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((member, bytes) in members) {
        zip.putNextEntry(ZipEntry(member))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return file
  }

  private fun check(file: File, md5: String?, crc: String? = null) =
    matchesServerHashes(Path(file.path), md5, null, crc)

  @Test
  fun checksAFileByItsBytes() {
    val file = File(dir, "game.gba").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertEquals(true, check(file, md5(byteArrayOf(1, 2, 3))))
    assertEquals(false, check(file, md5(byteArrayOf(1, 2))))
    assertEquals(true, check(file, md5 = null, crc = "55bc801d"))
    assertNull(check(file, md5 = null))
  }

  @Test
  fun checksAZipByItsContentAsRommHashesIt() {
    val b = byteArrayOf(4, 5, 6, 7)
    val a = byteArrayOf(1, 2)
    val file = zip("game.zip", mapOf("b.bin" to b, "a.bin" to a))
    // Every member in name order, for a ROM that's one zip.
    assertEquals(true, check(file, md5(a, b)))
    // The largest member, for a zip among a ROM's files.
    assertEquals(true, check(file, md5(b)))
    assertEquals(true, check(file, md5(file.readBytes())))
    assertEquals(false, check(file, md5(b, a)))
  }

  @Test
  fun cannotTellForOtherArchives() {
    val file = File(dir, "game.7z").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertNull(check(file, md5(byteArrayOf(9))))
  }
}
