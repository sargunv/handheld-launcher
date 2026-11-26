package dev.sargunv.handheld.data.romm

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write

class DownloadTest {
  private val content = Random(42).nextBytes(200_000)
  private val target =
    Path(SystemTemporaryDirectory, "download-test-${Random.nextLong().toULong()}.bin")

  @AfterTest fun deleteTarget() = SystemFileSystem.delete(target, mustExist = false)

  private fun writeTarget(bytes: ByteArray) =
    SystemFileSystem.sink(target).buffered().use { it.write(bytes) }

  private fun readTarget() = SystemFileSystem.source(target).buffered().use { it.readByteArray() }

  /** A server that serves [content], honoring ranges unless [ignoreRanges]. */
  private fun fileServer(ignoreRanges: Boolean = false, truncateAt: Int? = null) =
    MockServer { request ->
      val range =
        request.headers[HttpHeaders.Range]?.let { Regex("""bytes=(\d+)-""").matchEntire(it) }
      val start = range?.groupValues?.get(1)?.toInt()
      when {
        start == null || ignoreRanges -> {
          val sent = content.copyOf(truncateAt ?: content.size)
          respond(
            ByteReadChannel(sent),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentLength, "${content.size}"),
          )
        }
        start >= content.size ->
          respond(
            "",
            HttpStatusCode.RequestedRangeNotSatisfiable,
            headersOf(HttpHeaders.ContentRange, "bytes */${content.size}"),
          )
        else ->
          respond(
            ByteReadChannel(content.copyOfRange(start, content.size)),
            HttpStatusCode.PartialContent,
            headersOf(
              HttpHeaders.ContentRange to
                listOf("bytes $start-${content.size - 1}/${content.size}"),
              HttpHeaders.ContentLength to listOf("${content.size - start}"),
            ),
          )
      }
    }

  @Test
  fun downloadsAWholeFile() = runTest {
    val server = fileServer()
    val progress = mutableListOf<DownloadProgress>()
    val result = server.client.downloadRomFile(70, "Disc Game #1.chd", target) { progress += it }

    assertEquals(DownloadResult(totalBytes = content.size.toLong(), resumedFrom = 0), result)
    assertContentEquals(content, readTarget())
    val request = server.requests.single()
    assertEquals("/api/roms/70/files/content/Disc%20Game%20%231.chd", request.url.encodedPath)
    assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
    assertNull(request.headers[HttpHeaders.Range])
    assertEquals(DownloadProgress(0, content.size.toLong()), progress.first())
    assertEquals(DownloadProgress(content.size.toLong(), content.size.toLong()), progress.last())
    assertEquals(progress.sortedBy { it.bytesDownloaded }, progress)
  }

  @Test
  fun resumesAPartialFile() = runTest {
    writeTarget(content.copyOf(150_000))
    val server = fileServer()
    val result = server.client.downloadFirmware(3, "bios.bin", target)

    assertEquals(DownloadResult(totalBytes = content.size.toLong(), resumedFrom = 150_000), result)
    assertContentEquals(content, readTarget())
    assertEquals("/api/firmware/3/content/bios.bin", server.requests.single().url.encodedPath)
    assertEquals("bytes=150000-", server.requests.single().headers[HttpHeaders.Range])
  }

  @Test
  fun startsOverWhenTheServerIgnoresTheRange() = runTest {
    writeTarget(ByteArray(1000) { 1 })
    val result = fileServer(ignoreRanges = true).client.downloadRomFile(70, "game.bin", target)

    assertEquals(0, result.resumedFrom)
    assertContentEquals(content, readTarget())
  }

  @Test
  fun keepsACompleteFile() = runTest {
    writeTarget(content)
    val server = fileServer()
    val result = server.client.downloadRomFile(70, "game.bin", target)

    assertEquals(DownloadResult(content.size.toLong(), content.size.toLong()), result)
    assertContentEquals(content, readTarget())
    assertEquals(1, server.requests.size)
  }

  @Test
  fun startsOverWhenTheFileIsLongerThanTheServers() = runTest {
    writeTarget(content + ByteArray(10))
    val server = fileServer()
    server.client.downloadRomFile(70, "game.bin", target)

    assertContentEquals(content, readTarget())
    assertEquals(
      listOf("bytes=200010-", null),
      server.requests.map { it.headers[HttpHeaders.Range] },
    )
  }

  @Test
  fun keepsWhatArrivedWhenTheConnectionBreaks() = runTest {
    assertFailsWith<RommException.Unreachable> {
      fileServer(truncateAt = 120_000).client.downloadRomFile(70, "game.bin", target)
    }
    assertContentEquals(content.copyOf(120_000), readTarget())

    val result = fileServer().client.downloadRomFile(70, "game.bin", target)
    assertEquals(120_000, result.resumedFrom)
    assertContentEquals(content, readTarget())
  }

  @Test
  fun leavesTheFileAloneOnAnError() = runTest {
    writeTarget(content.copyOf(10))
    val server = MockServer { respond("""{"detail":"Not found"}""", HttpStatusCode.NotFound) }
    assertFailsWith<RommException.NotFound> {
      server.client.downloadRomFile(70, "game.bin", target)
    }
    assertContentEquals(content.copyOf(10), readTarget())
  }
}
