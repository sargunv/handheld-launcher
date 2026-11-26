package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.romm.TEST_SERVER
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.security.MessageDigest
import java.util.Collections
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path

class DownloadManagerTest {
  private val dir = createTempDirectory("downloads-test").toFile()
  private val storage = File(dir, "storage").apply { mkdirs() }
  private val queueFile = Path(File(dir, "downloads.json").path)
  private val library = LibraryCache.open(path = null)
  private val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())

  @AfterTest
  fun cleanUp() {
    library.close()
    dir.deleteRecursively()
  }

  private val game = Random(1).nextBytes(300_000)
  private val disc1 = Random(2).nextBytes(100_000)
  private val disc2 = Random(3).nextBytes(120_000)

  private fun md5(bytes: ByteArray) =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

  private class ServerFile(
    val id: Long,
    val name: String,
    val content: ByteArray,
    val folder: String,
    val size: Long = content.size.toLong(),
  )

  private fun fileJson(romId: Long, file: ServerFile, md5: String? = md5(file.content)) =
    """
    {
      "id": ${file.id}, "rom_id": $romId, "file_name": "${file.name}",
      "file_path": "${file.folder}", "file_size_bytes": ${file.size},
      "is_top_level": true, "updated_at": "2025-01-01T00:00:00+00:00",
      "md5_hash": ${md5?.let { "\"$it\"" } ?: "null"}, "category": "game"
    }
    """

  private val single = ServerFile(11, "Game (USA).gba", game, "roms/gba")
  private val discs =
    listOf(
      ServerFile(22, "Saga (USA) (Disc 2).chd", disc2, "roms/psx/Saga (USA)"),
      ServerFile(21, "Saga (USA) (Disc 1).chd", disc1, "roms/psx/Saga (USA)"),
    )

  /** The server's ROMs: a one-file game on the GBA, and a two-disc game on the PlayStation. */
  private fun romJson(id: Long, sizeOverride: Long? = null): String =
    when (id) {
      1L ->
        Fixtures.rom(
          1,
          platformSlug = "gba",
          fsName = single.name,
          files =
            listOf(
              fileJson(
                1,
                if (sizeOverride == null) single
                else ServerFile(single.id, single.name, game, single.folder, sizeOverride),
              ),
            ),
        )
      2L ->
        Fixtures.rom(
          2,
          platformId = 2,
          platformSlug = "psx",
          fsName = "Saga (USA)",
          files = discs.map { fileJson(2, it) },
        )
      else -> error("No ROM $id")
    }

  private fun Rom(id: Long, platformId: Long, fileName: String, files: List<ServerFile>) =
    Rom(
      id = id,
      platformId = platformId,
      platformSlug = if (platformId == 1L) "gba" else "psx",
      title = "Game $id",
      sortKey = "game $id",
      alternativeNames = emptyList(),
      fileName = fileName,
      sizeBytes = files.sumOf { it.size },
      summary = null,
      genres = emptyList(),
      regions = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      hasMultipleFiles = files.size > 1,
      files = files.map { RomFile(it.id, it.name, it.size, null) },
      updatedAt = Instant.fromEpochSeconds(0),
      createdAt = Instant.fromEpochSeconds(0),
    )

  private val gameRom = Rom(1, 1, single.name, listOf(single))
  private val sagaRom = Rom(2, 2, "Saga (USA)", discs)

  private suspend fun fillLibrary() {
    library.replacePlatforms(
      listOf(
        dev.sargunv.handheld.domain.Platform(1, "gba", "gba", "Game Boy Advance", 1),
        dev.sargunv.handheld.domain.Platform(2, "psx", "psx", "PlayStation", 1),
      ),
    )
  }

  /** Serves a file's bytes from where the request's range starts, as RomM's file endpoint does. */
  private fun MockRequestHandleScope.serve(request: HttpRequestData, content: ByteArray) =
    serveFrom(request, content) { ByteReadChannel(it) }

  private fun MockRequestHandleScope.serveFrom(
    request: HttpRequestData,
    content: ByteArray,
    channel: (ByteArray) -> ByteReadChannel,
  ): HttpResponseData {
    val start =
      request.headers[HttpHeaders.Range]
        ?.let { Regex("""bytes=(\d+)-""").find(it) }
        ?.groupValues
        ?.get(1)
        ?.toInt()
    return if (start == null) {
      respond(
        channel(content),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentLength, "${content.size}"),
      )
    } else {
      respond(
        channel(content.copyOfRange(start, content.size)),
        HttpStatusCode.PartialContent,
        headersOf(
          HttpHeaders.ContentRange to listOf("bytes $start-${content.size - 1}/${content.size}"),
          HttpHeaders.ContentLength to listOf("${content.size - start}"),
        ),
      )
    }
  }

  /** A server for [romJson] whose files have [contents], answering installs with [installs]. */
  private fun server(
    contents: Map<Long, ByteArray> = mapOf(11L to game, 21L to disc1, 22L to disc2),
    sizeOverride: Long? = null,
    installs: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
    content: suspend MockRequestHandleScope.(HttpRequestData, ByteArray) -> HttpResponseData =
      { request, bytes ->
        serve(request, bytes)
      },
  ): () -> MockEngine = {
    MockEngine { request ->
      requests += request
      installs(request)?.let {
        return@MockEngine it
      }
      val path = request.apiPath
      val file = Regex("""roms/(\d+)/files/content/.+""").matchEntire(path)
      when {
        file != null -> content(request, contents.getValue(file.groupValues[1].toLong()))
        path.matches(Regex("""roms/\d+""")) ->
          json(romJson(path.substringAfter('/').toLong(), sizeOverride))
        else -> respondError(HttpStatusCode.NotFound)
      }
    }
  }

  private fun manager(
    scope: CoroutineScope,
    engine: () -> MockEngine,
    deviceId: String? = null,
    canWrite: Flow<Boolean> = flowOf(true),
    targets: List<StorageTarget> = listOf(StorageTarget(storage.path, "Test storage")),
    storageSettings: StorageSettings = StorageSettings(),
  ) =
    DownloadManager(
      queueFile = queueFile,
      library = library,
      credentials = InMemoryCredentialStore(RommCredentials(TEST_SERVER, "rmm_test", deviceId)),
      storage = { targets },
      scope = scope,
      engine = engine,
      canWrite = canWrite,
      storageSettings = { storageSettings },
    )

  private suspend fun DownloadManager.awaitEmpty() = waitFor {
    queue.first { it.isEmpty() }
  }

  private fun file(path: String) = File(storage, path)

  /** Runs [block] with a timeout in real time, since the downloads run on real threads. */
  private suspend fun <T> waitFor(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000, block) }

  @Test
  fun downloadsAGameIntoItsPlatformFolder() = runTest {
    fillLibrary()
    val downloads = manager(backgroundScope, server())
    assertEquals(RomStatus.NotOnDevice, downloads.status(gameRom).first())

    assertTrue(downloads.enqueue(gameRom))
    downloads.awaitEmpty()

    val target = file("ROMs/gba/Game (USA).gba")
    assertContentEquals(game, target.readBytes())
    assertEquals(RomStatus.OnDevice(target.path), downloads.status(gameRom).first())
    assertEquals(listOf("roms/11/files/content/Game%20(USA).gba"), contentRequests())
    // A game on the device isn't queued again.
    assertFalse(downloads.enqueue(gameRom))
  }

  private fun contentRequests() =
    requests
      .filter { "files/content" in it.apiPath }
      .map { it.url.encodedPath.substringAfter("/api/") }

  /**
   * Starts downloading the GBA game from a server that sends [sent] and then stalls, and stops the
   * app once those bytes arrive, as when the process is killed.
   */
  private suspend fun stopMidDownload(sent: ByteArray) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stalling =
      server(
        content = { request, bytes ->
          serveFrom(request, bytes) {
            ByteChannel(autoFlush = true).also { channel ->
              @Suppress("OPT_IN_USAGE") GlobalScope.launch { channel.writeFully(sent) }
            }
          }
        },
      )
    val downloads = manager(scope, stalling)
    downloads.enqueue(gameRom)
    waitFor {
      downloads.queue.first { list -> list.firstOrNull()?.bytesDownloaded == sent.size.toLong() }
    }
    scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
  }

  @Test
  fun resumesAfterARestart() = runTest {
    fillLibrary()
    stopMidDownload(game.copyOf(131_072))
    assertEquals(131_072, file("ROMs/gba/Game (USA).gba.part").length())
    assertFalse(file("ROMs/gba/Game (USA).gba").exists())
    requests.clear()

    // The app starts again, with the queue it stored.
    val downloads = manager(backgroundScope, server())
    assertEquals(listOf(1L), downloads.queue.value.map { it.romId })
    downloads.awaitEmpty()

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertFalse(file("ROMs/gba/Game (USA).gba.part").exists())
    val resumed = requests.single { "files/content" in it.apiPath }
    assertEquals("bytes=131072-", resumed.headers[HttpHeaders.Range])
  }

  @Test
  fun downloadsAgainWhenAResumedFileDoesNotMatch() = runTest {
    fillLibrary()
    // The first attempt got the start of a file that changed on the server since.
    stopMidDownload(ByteArray(131_072) { 7 })
    requests.clear()

    val downloads = manager(backgroundScope, server())
    downloads.awaitEmpty()

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertEquals(
      listOf("bytes=131072-", null),
      requests.filter { "files/content" in it.apiPath }.map { it.headers[HttpHeaders.Range] },
    )
  }

  @Test
  fun cancelsADownloadAndDeletesWhatArrived() = runTest {
    fillLibrary()
    val stalling =
      server(
        content = { request, bytes ->
          serveFrom(request, bytes) {
            ByteChannel(autoFlush = true).also { channel ->
              @Suppress("OPT_IN_USAGE")
              GlobalScope.launch { channel.writeFully(game.copyOf(65_536)) }
            }
          }
        },
      )
    val downloads = manager(backgroundScope, stalling)
    downloads.enqueue(gameRom)
    waitFor {
      downloads.queue.first { list -> list.firstOrNull()?.bytesDownloaded == 65_536L }
    }

    downloads.cancel(1)

    assertEquals(emptyList(), downloads.queue.value)
    assertFalse(file("ROMs/gba/Game (USA).gba.part").exists())
    assertEquals(RomStatus.NotOnDevice, downloads.status(gameRom).first())
  }

  @Test
  fun downloadsEveryDiscOfAGameWithAPlaylist() = runTest {
    fillLibrary()
    val downloads = manager(backgroundScope, server())

    downloads.enqueue(sagaRom)
    downloads.awaitEmpty()

    val folder = file("ROMs/psx/Saga (USA)")
    assertEquals(RomStatus.OnDevice(folder.path), downloads.status(sagaRom).first())
    assertContentEquals(disc1, File(folder, "Saga (USA) (Disc 1).chd").readBytes())
    assertContentEquals(disc2, File(folder, "Saga (USA) (Disc 2).chd").readBytes())
    assertEquals(
      "Saga (USA) (Disc 1).chd\nSaga (USA) (Disc 2).chd\n",
      File(folder, "Saga (USA).m3u").readText(),
    )
    assertFalse(file("ROMs/psx/Saga (USA).part").exists())
  }

  @Test
  fun downloadsSkipStorageThatsOffButStillFindGamesOnIt() = runTest {
    fillLibrary()
    library.upsertRoms(listOf(gameRom, sagaRom))
    val card = File(dir, "card").apply { mkdirs() }
    val own = StorageTarget(storage.path, "Device", "primary")
    val sd = StorageTarget(card.path, "SD card", "card")
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
    val downloads =
      manager(
        backgroundScope,
        server(),
        targets = listOf(own, sd),
        storageSettings = StorageSettings(off = setOf("primary")),
      )

    // The game on the device's own storage still counts, though downloads skip it.
    assertEquals(
      RomStatus.OnDevice(gba.path),
      waitFor { downloads.statuses().first { it.isNotEmpty() } }[1L],
    )
    assertTrue(downloads.enqueue(sagaRom))
    downloads.awaitEmpty()
    assertTrue(File(card, "ROMs/psx/Saga (USA)").isDirectory)
    assertFalse(file("ROMs/psx").exists())
  }

  @Test
  fun listsTheLibrarysGamesOnTheDeviceAndInTheQueue() = runTest {
    fillLibrary()
    library.upsertRoms(listOf(gameRom, sagaRom))
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
    // A download that stopped partway isn't the game.
    file("ROMs/psx/Saga (USA).part").mkdirs()
    val downloads = manager(backgroundScope, server())

    assertEquals(
      mapOf(1L to RomStatus.OnDevice(gba.path)),
      waitFor { downloads.statuses().first { it.isNotEmpty() } },
    )
    downloads.enqueue(sagaRom)
    assertEquals(
      RomStatus.OnDevice(file("ROMs/psx/Saga (USA)").path),
      waitFor { downloads.statuses().first { it[2L] is RomStatus.OnDevice } }[2L],
    )
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
    val downloads = manager(backgroundScope, server())
    waitFor { downloads.statuses().first { it.size == 2 } }

    assertEquals(Removal.Removed, downloads.remove(gameRom))
    assertFalse(gba.exists())
    assertTrue(gbaSave.exists())
    assertEquals(RomStatus.NotOnDevice, downloads.status(gameRom).first())
    assertEquals(setOf(2L), waitFor { downloads.statuses().first { it.size == 1 } }.keys)

    // A folder that holds something besides the game stays whole.
    assertEquals(Removal.HoldsOtherFiles(listOf(discSave.name)), downloads.remove(sagaRom))
    assertEquals(4, folder.walk().count { it.isFile })
    discSave.delete()
    // So does one with a file that only shares a name with one of the game's.
    val stray = File(folder, "extras/Saga (USA) (Disc 1).chd").apply { parentFile.mkdirs() }
    stray.writeText("not the game")
    assertEquals(Removal.HoldsOtherFiles(listOf(stray.name)), downloads.remove(sagaRom))
    assertTrue(stray.exists())
    stray.delete()
    assertEquals(Removal.Removed, downloads.remove(sagaRom))
    assertFalse(folder.exists())
    assertEquals(emptyMap(), waitFor { downloads.statuses().first { it.isEmpty() } })
    assertEquals(Removal.NotOnDevice, downloads.remove(sagaRom))
  }

  @Test
  fun saysSoWhenAGameCantBeRemoved() = runTest {
    fillLibrary()
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
    val downloads = manager(backgroundScope, server())
    // A folder the app can't write to, as on a read-only card.
    gba.parentFile.setWritable(false)
    try {
      assertIs<Removal.Failed>(downloads.remove(gameRom))
      assertTrue(gba.exists())
    } finally {
      gba.parentFile.setWritable(true)
    }
  }

  @Test
  fun refusesAGameThatDoesNotFit() = runTest {
    fillLibrary()
    val downloads = manager(backgroundScope, server(sizeOverride = 1L shl 60))

    downloads.enqueue(gameRom)
    val failed = waitFor {
      downloads.queue.first { it.firstOrNull()?.state == DownloadState.Failed }.single()
    }

    assertTrue(failed.error.orEmpty().startsWith("Not enough space on Test storage"), failed.error)
    assertEquals(emptyList(), contentRequests())
    assertEquals(RomStatus.Failed(failed.error!!), downloads.status(gameRom).first())
    assertFalse(file("ROMs/gba").exists())
  }

  @Test
  fun takesGamesSentFromTheServerAndReportsThem() = runTest {
    fillLibrary()
    // The PlayStation game is on the device already.
    File(storage, "ROMs/psx/Saga (USA)").mkdirs()
    val updates = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    fun request(id: String, romId: Long, status: String) =
      """{"id": "$id", "user_id": 1, "device_id": "dev-1", "rom_id": $romId, "file_ids": [],
        "status": "$status", "reason": null, "created_at": "2025-01-01T00:00:00Z",
        "updated_at": "2025-01-01T00:00:00Z"}"""
    val installs: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? =
      { request ->
        val path = request.apiPath
        when {
          path == "devices/dev-1/installs" && request.method == HttpMethod.Get ->
            json("[${request("req-1", 1, "pending")}, ${request("req-2", 2, "pending")}]")
          path == "devices/dev-1/installs/claim" && request.method == HttpMethod.Post ->
            json("[${request("req-1", 1, "taken")}, ${request("req-2", 2, "taken")}]")
          path.startsWith("devices/dev-1/installs/") && request.method == HttpMethod.Put -> {
            val id = path.substringAfterLast('/')
            updates += id to (request.body as TextContent).text
            json(request(id, if (id == "req-1") 1 else 2, "done"))
          }
          else -> null
        }
      }
    val downloads = manager(backgroundScope, server(installs = installs), deviceId = "dev-1")

    downloads.takeInstallRequests()
    downloads.awaitEmpty()
    // The download reports to the server once it's out of the queue.
    waitFor { while (updates.size < 2) delay(10) }

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertEquals(
      setOf("req-2" to """{"status":"already_installed"}""", "req-1" to """{"status":"done"}"""),
      updates.toSet(),
    )
    assertEquals(1, requests.count { it.apiPath.endsWith("claim") })
  }

  @Test
  fun cancellingAGameSentFromTheServerCancelsItsRequest() = runTest {
    fillLibrary()
    val sent =
      """{"id": "req-1", "user_id": 1, "device_id": "dev-1", "rom_id": 1, "file_ids": [],
        "status": "taken", "reason": null, "created_at": "2025-01-01T00:00:00Z",
        "updated_at": "2025-01-01T00:00:00Z"}"""
    val installs: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? =
      { request ->
        when (request.apiPath) {
          "devices/dev-1/installs" -> json("[$sent]")
          "devices/dev-1/installs/req-1" -> json(sent)
          else -> null
        }
      }
    val engine =
      server(
        installs = installs,
        content = { request, bytes ->
          serveFrom(request, bytes) {
            ByteChannel(autoFlush = true).also { channel ->
              @Suppress("OPT_IN_USAGE")
              GlobalScope.launch { channel.writeFully(game.copyOf(65_536)) }
            }
          }
        },
      )
    val downloads = manager(backgroundScope, engine, deviceId = "dev-1")
    downloads.takeInstallRequests()
    waitFor { downloads.queue.first { it.firstOrNull()?.bytesDownloaded == 65_536L } }

    downloads.cancel(1)

    val ended = requests.filter { it.apiPath == "devices/dev-1/installs/req-1" }
    assertEquals(listOf(HttpMethod.Delete), ended.map { it.method })
  }

  @Test
  fun aGameSentAgainAfterItFailedDownloadsAgain() = runTest {
    fillLibrary()
    val updates = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    var sent = "req-1"
    var fits = false
    fun request(id: String) =
      """{"id": "$id", "user_id": 1, "device_id": "dev-1", "rom_id": 1, "file_ids": [],
        "status": "taken", "reason": null, "created_at": "2025-01-01T00:00:00Z",
        "updated_at": "2025-01-01T00:00:00Z"}"""
    val installs: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? =
      { request ->
        val path = request.apiPath
        when {
          path == "devices/dev-1/installs" -> json("[${request(sent)}]")
          path.startsWith("devices/dev-1/installs/") && request.method == HttpMethod.Put -> {
            val id = path.substringAfterLast('/')
            updates += id to (request.body as TextContent).text
            json(request(id))
          }
          else -> null
        }
      }
    val tooBig = server(sizeOverride = 1L shl 60, installs = installs)
    val fitting = server(installs = installs)
    val downloads = manager(backgroundScope, { if (fits) fitting() else tooBig() }, "dev-1")

    downloads.takeInstallRequests()
    waitFor { downloads.queue.first { it.firstOrNull()?.state == DownloadState.Failed } }

    // Sent again from the web page once there's room.
    sent = "req-2"
    fits = true
    downloads.takeInstallRequests()
    downloads.awaitEmpty()
    waitFor { while (updates.none { it.first == "req-2" }) delay(10) }

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertEquals(listOf("req-2" to """{"status":"done"}"""), updates.filter { it.first == "req-2" })
  }

  @Test
  fun waitsForStorageAccessInsteadOfFailing() = runTest {
    fillLibrary()
    val access = MutableStateFlow(false)
    val downloads = manager(backgroundScope, server(), canWrite = access)

    downloads.enqueue(gameRom)
    delay(200)
    assertEquals(DownloadState.Queued, downloads.queue.value.single().state)
    assertEquals(emptyList(), contentRequests())

    access.value = true
    downloads.awaitEmpty()
    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
  }

  @Test
  fun aCancelFinishesWhenItsCallerGoesAway() = runTest {
    fillLibrary()
    val stalling =
      server(
        content = { request, bytes ->
          serveFrom(request, bytes) {
            ByteChannel(autoFlush = true).also { channel ->
              @Suppress("OPT_IN_USAGE")
              GlobalScope.launch { channel.writeFully(game.copyOf(65_536)) }
            }
          }
        },
      )
    val downloads = manager(backgroundScope, stalling)
    downloads.enqueue(gameRom)
    waitFor {
      downloads.queue.first { list -> list.firstOrNull()?.bytesDownloaded == 65_536L }
    }

    // The screen that canceled goes away while the cancel waits for the download to stop.
    val caller =
      launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { downloads.cancel(1) }
    caller.cancel()

    waitFor { downloads.queue.first { it.isEmpty() } }
    assertFalse(file("ROMs/gba/Game (USA).gba.part").exists())
  }
}
