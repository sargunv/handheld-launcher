package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.LocalGame
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class UploadsTest {
  private val dir = createTempDirectory("uploads-test").toFile()
  private val content = ByteArray(10) { it.toByte() }
  private val file =
    File(dir, "ROMs/gb/Homebrë.gb").apply {
      parentFile.mkdirs()
      writeBytes(content)
    }
  private val game =
    LocalGame(file.path, 42, "gb", content.size.toLong(), Instant.fromEpochSeconds(0))

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  private val HttpRequestData.bytes: ByteArray
    get() = (body as OutgoingContent.ByteArrayContent).bytes()

  /** A server that takes the upload and runs scans, reporting [scanStatuses] in turn. */
  private fun server(vararg scanStatuses: String) = MockServer { request ->
    when {
      request.apiPath == "roms/upload/start" ->
        json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
      request.apiPath == "roms/upload/u1" -> json("""{"received": 1, "total": 3}""")
      request.apiPath == "roms/upload/u1/complete" -> respond("", HttpStatusCode.Created)
      request.apiPath == "roms/upload/u1/cancel" -> respond("", HttpStatusCode.NoContent)
      request.apiPath == "tasks/scan" -> json(task("queued"), HttpStatusCode.Accepted)
      request.apiPath == "tasks/t1" -> json(task(scanStatuses.getOrElse(scans++) { "finished" }))
      else -> json("""{"detail": "Not Found"}""", HttpStatusCode.NotFound)
    }
  }

  private var scans = 0

  private fun task(status: String) =
    """{"task_key": null, "task_name": "scan_platforms", "task_id": "t1", "status": "$status",
    "created_at": null, "enqueued_at": null}"""

  @Test
  fun uploadsTheFileInChunksAndScansItsPlatform() = runTest {
    val server = server("queued", "started", "finished")
    val progress = mutableListOf<Long>()

    val result =
      uploadLocalGame(server.client, game, chunkSize = 4, scanPoll = 1.milliseconds) { sent, total
        ->
        assertEquals(10, total)
        progress += sent
      }

    assertEquals(UploadResult.Added, result)
    assertEquals(listOf(4L, 8L, 10L), progress)
    val start = server.requests.first()
    assertEquals("roms/upload/start", start.apiPath)
    assertEquals(HttpMethod.Post, start.method)
    assertEquals("42", start.headers["x-upload-platform"])
    // A header carries only Latin-1, so the body names the file.
    assertEquals("Homebr_.gb", start.headers["x-upload-filename"])
    assertEquals("""{"filename":"Homebrë.gb"}""", (start.body as TextContent).text)
    assertEquals("10", start.headers["x-upload-total-size"])
    assertEquals("3", start.headers["x-upload-total-chunks"])
    val chunks = server.requests.filter { it.apiPath == "roms/upload/u1" }
    assertEquals(listOf("0", "1", "2"), chunks.map { it.headers["x-chunk-index"] })
    assertEquals(HttpMethod.Put, chunks.first().method)
    assertContentEquals(content, chunks.flatMap { it.bytes.toList() }.toByteArray())
    assertEquals(
      listOf("roms/upload/u1/complete", "tasks/scan", "tasks/t1", "tasks/t1", "tasks/t1"),
      server.requests.drop(4).map { it.apiPath },
    )
    assertEquals("""{"platforms":[42]}""", (server.requests[5].body as TextContent).text)
  }

  @Test
  fun aRefusedUploadSendsNothing() = runTest {
    val server = MockServer {
      json("""{"detail": "Forbidden"}""", HttpStatusCode.Forbidden)
    }
    val error =
      assertFailsWith<RommException.Forbidden> {
        uploadLocalGame(server.client, game, chunkSize = 4)
      }
    assertEquals("Not allowed · Forbidden", error.message)
    assertEquals(listOf("roms/upload/start"), server.requests.map { it.apiPath })
  }

  @Test
  fun aFailedChunkCancelsTheUploadOnTheServer() = runTest {
    val server = MockServer { request ->
      when (request.apiPath) {
        "roms/upload/start" -> json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
        "roms/upload/u1/cancel" -> respond("", HttpStatusCode.NoContent)
        else ->
          json(
            """{"detail": "Chunk exceeds maximum allowed size"}""",
            HttpStatusCode.PayloadTooLarge,
          )
      }
    }
    val error =
      assertFailsWith<RommException.ClientError> {
        uploadLocalGame(server.client, game, chunkSize = 4)
      }
    assertEquals("Chunk exceeds maximum allowed size", error.detail)
    assertEquals(
      listOf("roms/upload/start", "roms/upload/u1", "roms/upload/u1/cancel"),
      server.requests.map { it.apiPath },
    )
  }

  @Test
  fun aCanceledUploadIsCanceledOnTheServer() = runTest {
    val chunkArrived = CompletableDeferred<Unit>()
    val server = MockServer { request ->
      when (request.apiPath) {
        "roms/upload/start" -> json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
        "roms/upload/u1/cancel" -> respond("", HttpStatusCode.NoContent)
        else -> {
          chunkArrived.complete(Unit)
          CompletableDeferred<Unit>().await()
          error("unreachable")
        }
      }
    }
    val upload = async { uploadLocalGame(server.client, game, chunkSize = 4) }
    chunkArrived.await()
    upload.cancelAndJoin()
    assertEquals("roms/upload/u1/cancel", server.requests.last().apiPath)
  }

  @Test
  fun chunksAreSizedAsTheServerSplitsTheTotal() = runTest {
    file.writeBytes(content.copyOf(9))
    val server = server()
    uploadLocalGame(server.client, game.copy(sizeBytes = 9), chunkSize = 4)
    // Three chunks of a 9-byte file are 3 bytes each, as the server expects, not 4, 4 and 1.
    assertEquals(
      listOf(3, 3, 3),
      server.requests.filter { it.apiPath == "roms/upload/u1" }.map { it.bytes.size },
    )
  }

  @Test
  fun anUploadCanceledWhileStartingIsCanceledOnTheServer() = runTest {
    val started = CompletableDeferred<Unit>()
    val answer = CompletableDeferred<Unit>()
    val server = MockServer { request ->
      when (request.apiPath) {
        "roms/upload/start" -> {
          started.complete(Unit)
          answer.await()
          json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
        }
        "roms/upload/u1/cancel" -> respond("", HttpStatusCode.NoContent)
        else -> error("Nothing else is sent: ${request.apiPath}")
      }
    }
    val upload = async { uploadLocalGame(server.client, game, chunkSize = 4) }
    started.await()
    upload.cancel()
    answer.complete(Unit)
    upload.join()
    assertEquals(
      listOf("roms/upload/start", "roms/upload/u1/cancel"),
      server.requests.map { it.apiPath },
    )
  }

  @Test
  fun aScanThatCantBeFollowedStillCountsTheUpload() = runTest {
    val server = MockServer { request ->
      when (request.apiPath) {
        "roms/upload/start" -> json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
        "roms/upload/u1" -> json("{}")
        "roms/upload/u1/complete" -> respond("", HttpStatusCode.Created)
        "tasks/scan" -> json(task("queued"), HttpStatusCode.Accepted)
        else -> json("""{"detail": "Unavailable"}""", HttpStatusCode.ServiceUnavailable)
      }
    }
    assertEquals(UploadResult.AwaitingScan, uploadLocalGame(server.client, game))
  }

  @Test
  fun anAccountThatCantScanLeavesTheGameForTheServersNextScan() = runTest {
    val server = MockServer { request ->
      when (request.apiPath) {
        "roms/upload/start" -> json("""{"upload_id": "u1"}""", HttpStatusCode.Created)
        "roms/upload/u1" -> json("{}")
        "roms/upload/u1/complete" -> respond("", HttpStatusCode.Created)
        else -> json("""{"detail": "Forbidden"}""", HttpStatusCode.Forbidden)
      }
    }
    assertEquals(UploadResult.AwaitingScan, uploadLocalGame(server.client, game))
    assertEquals("tasks/scan", server.requests.last().apiPath)
  }
}
