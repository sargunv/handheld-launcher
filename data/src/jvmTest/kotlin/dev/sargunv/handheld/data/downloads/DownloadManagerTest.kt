package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommState
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
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

  private fun md5(bytes: ByteArray) = hash("MD5", bytes)

  private fun hash(algorithm: String, bytes: ByteArray) =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

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
    library.upsertRoms(listOf(gameRom, sagaRom))
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
    sizeOverride: () -> Long? = { null },
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
          json(romJson(path.substringAfter('/').toLong(), sizeOverride()))
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
    romm: StateFlow<RommState> = MutableStateFlow(account(engine, deviceId = deviceId)),
  ) =
    DownloadManager(
      queueFile = queueFile,
      library = library,
      games = DeviceGames(library, { targets }, scope, Path(File(dir, "local-content.json").path)),
      romm = romm,
      storage = { targets },
      scope = scope,
      canWrite = canWrite,
      storageSettings = { storageSettings },
    )

  /** Signed in to [server] at [engine] with [token]. */
  private fun account(
    engine: () -> MockEngine,
    token: String = "rmm_test",
    server: String = TEST_SERVER,
    deviceId: String? = null,
  ) =
    RommAccount(
      RommCredentials(server, token, 1, "player", deviceId),
      RommClient(server, token, engine()),
      Path(dir.path, "save-sync"),
    )

  /**
   * A scope on real threads, so each sign-in polls once for the games sent to the device. On the
   * test's scheduler, the minute between polls passes whenever the test waits for a download.
   */
  private fun TestScope.realTime() =
    CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)

  private suspend fun DownloadManager.awaitEmpty() = waitFor {
    queue.first { it.isEmpty() }
  }

  private fun file(path: String) = File(storage, path)

  /** The folder the ROM [romId] downloads in until it's whole. */
  private fun staged(romId: Long) = file("ROMs/.handheld-staging/$romId")

  /** Runs [block] with a timeout in real time, since the downloads run on real threads. */
  private suspend fun <T> waitFor(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000, block) }

  /**
   * Every status [rom] takes in [downloads] from now on, in order. The collector runs unconfined,
   * in the thread of each change, so no status is skipped.
   */
  private fun TestScope.statuses(downloads: DownloadManager, rom: Rom): StateFlow<List<RomStatus>> {
    val seen = MutableStateFlow(emptyList<RomStatus>())
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      downloads.status(rom).collect { status -> seen.update { it + status } }
    }
    return seen
  }

  /**
   * Asserts that [seen] went from not on the device to queued, through a download of [size] bytes
   * whose progress only grew, and straight on to the device at [path].
   */
  private fun assertDownloaded(seen: List<RomStatus>, size: Long, path: File) {
    assertEquals(listOf(RomStatus.NotOnDevice, RomStatus.Queued), seen.take(2), "$seen")
    assertEquals(RomStatus.OnDevice(path.path), seen.last(), "$seen")
    val downloading = seen.subList(2, seen.size - 1)
    assertTrue(downloading.all { it is RomStatus.Downloading }, "$seen")
    val bytes = downloading.map { (it as RomStatus.Downloading).bytesDownloaded }
    assertEquals(bytes.sorted(), bytes, "$seen")
    assertEquals(RomStatus.Downloading(size, size), downloading.last())
  }

  @Test
  fun downloadsAGameIntoItsPlatformFolder() = runTest {
    fillLibrary()
    val downloads = manager(backgroundScope, server())
    val seen = statuses(downloads, gameRom)
    waitFor { seen.first { it.isNotEmpty() } }

    assertTrue(downloads.enqueue(gameRom))
    downloads.awaitEmpty()

    val target = file("ROMs/gba/Game (USA).gba")
    assertContentEquals(game, target.readBytes())
    // The progress only grows, and the finished download goes straight to on the device.
    assertDownloaded(seen.value, game.size.toLong(), target)
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
      downloads.progress.first { it[1L]?.bytesDownloaded == sent.size.toLong() }
    }
    scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
  }

  /** The tokens the content requests were made with, in order. */
  private fun contentTokens() =
    requests
      .filter { "files/content" in it.apiPath }
      .map { it.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") }

  // Signing in again to the same server keeps the queue, which waits for the sign-in.
  @Test
  fun aQueuedGameWaitsForItsServersSignIn() = runTest {
    fillLibrary()
    val engine = server()
    val romm = MutableStateFlow<RommState>(account(engine))
    // The queue can't start before the sign-out, since it waits for storage access.
    val writable = MutableStateFlow(false)
    val downloads = manager(backgroundScope, engine, canWrite = writable, romm = romm)
    val seen = statuses(downloads, gameRom)
    waitFor { seen.first { it.isNotEmpty() } }
    assertTrue(downloads.enqueue(gameRom))
    romm.value = RommState.SignedOut
    writable.value = true

    romm.value = account(engine, "rmm_new")
    downloads.awaitEmpty()
    val target = file("ROMs/gba/Game (USA).gba")
    assertContentEquals(game, target.readBytes())
    assertDownloaded(seen.value, game.size.toLong(), target)
    assertEquals(listOf("rmm_new"), contentTokens())
  }

  // The ids are the first server's, so another server's sign-in drops the games, even after a
  // restart in between.
  @Test
  fun aSignInToAnotherServerDropsTheQueue() = runTest {
    fillLibrary()
    val engine = server()
    val romm = MutableStateFlow<RommState>(account(engine))
    val writable = MutableStateFlow(false)
    val first = manager(backgroundScope, engine, canWrite = writable, romm = romm)
    assertTrue(first.enqueue(gameRom))
    romm.value = RommState.SignedOut

    val restarted = manager(backgroundScope, engine, canWrite = writable, romm = romm)
    romm.value = account(engine, "rmm_other", "https://other.test")
    writable.value = true
    restarted.awaitEmpty()
    assertEquals(emptyList(), contentRequests())
  }

  // A game downloads with the sign-in it started under, and the next one waits for a sign-in.
  @Test
  fun signingOutAsADownloadStartsLeavesTheNextGameQueued() = runTest {
    fillLibrary()
    val engine = server()
    val romm = MutableStateFlow<RommState>(account(engine))
    val writable = MutableStateFlow(false)
    val downloads = manager(backgroundScope, engine, canWrite = writable, romm = romm)
    val first = MutableStateFlow(emptyList<RomStatus>())
    // The device signs out as the first game starts, before it asks the server for anything.
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      downloads.status(gameRom).collect { status ->
        if (status is RomStatus.Downloading && first.value.none { it is RomStatus.Downloading }) {
          romm.value = RommState.SignedOut
        }
        first.update { it + status }
      }
    }
    val next = statuses(downloads, sagaRom)
    waitFor {
      first.first { it.isNotEmpty() }
      next.first { it.isNotEmpty() }
    }
    assertTrue(downloads.enqueue(gameRom))
    assertTrue(downloads.enqueue(sagaRom))
    writable.value = true

    waitFor { downloads.queue.first { queue -> queue.map { it.romId } == listOf(2L) } }
    val target = file("ROMs/gba/Game (USA).gba")
    assertDownloaded(first.value, game.size.toLong(), target)
    assertEquals(listOf(RomStatus.NotOnDevice, RomStatus.Queued), next.value)
    assertEquals(listOf("rmm_test"), contentTokens())

    romm.value = account(engine, "rmm_new")
    downloads.awaitEmpty()
    val folder = file("ROMs/psx/Saga (USA)")
    assertDownloaded(next.value, disc1.size + disc2.size.toLong(), folder)
    assertEquals(listOf("rmm_test", "rmm_new", "rmm_new"), contentTokens())
  }

  @Test
  fun doesNotDownloadAGameCopiedUnderAnotherName() = runTest {
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
    val downloads = manager(backgroundScope, server())

    assertEquals(
      RomStatus.OnDevice(copied.path),
      waitFor { downloads.status(rom).first { it is RomStatus.OnDevice } },
    )
    assertFalse(downloads.enqueue(rom))
    assertEquals(emptyList(), contentRequests())
  }

  @Test
  fun deletesWhatsStagedForGamesNotInTheQueue() = runTest {
    // The app starts with its data cleared, so the queue is empty.
    for (file in listOf(File(staged(1), single.name), File(staged(2), "Saga/Disc 1.chd"))) {
      file.parentFile.mkdirs()
      file.writeBytes(disc1)
    }
    manager(backgroundScope, server())
    waitFor { while (staged(1).exists() || staged(2).exists()) delay(10) }
  }

  @Test
  fun resumesAfterARestart() = runTest {
    fillLibrary()
    stopMidDownload(game.copyOf(131_072))
    assertEquals(131_072, File(staged(1), single.name).length())
    assertFalse(file("ROMs/gba/Game (USA).gba").exists())
    requests.clear()

    // The app starts again, with the queue it stored.
    val downloads = manager(backgroundScope, server())
    assertEquals(listOf(1L), downloads.queue.value.map { it.romId })
    downloads.awaitEmpty()

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertFalse(staged(1).exists())
    val resumed = requests.single { "files/content" in it.apiPath }
    assertEquals("bytes=131072-", resumed.headers[HttpHeaders.Range])
  }

  @Test
  fun aGameInPlaceStaysOnTheDeviceWhenItsStagingCantBeDeleted() = runTest {
    fillLibrary()
    // An earlier attempt staged the game under a name the server has changed since, in a folder
    // that can't be deleted from.
    val leftover = File(staged(1), "Old (USA)").apply { mkdirs() }
    File(leftover, "Old (USA).gba").writeBytes(disc1)
    leftover.setWritable(false)
    try {
      val writable = MutableStateFlow(false)
      val downloads = manager(backgroundScope, server(), canWrite = writable)
      val seen = statuses(downloads, gameRom)
      waitFor { seen.first { it.isNotEmpty() } }
      assertTrue(downloads.enqueue(gameRom))
      writable.value = true
      downloads.awaitEmpty()

      val target = file("ROMs/gba/Game (USA).gba")
      assertContentEquals(game, target.readBytes())
      assertDownloaded(seen.value, game.size.toLong(), target)
      assertTrue(File(leftover, "Old (USA).gba").exists())
    } finally {
      leftover.setWritable(true)
    }
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
      downloads.progress.first { it[1L]?.bytesDownloaded == 65_536L }
    }

    downloads.cancel(1)

    assertEquals(emptyList(), downloads.queue.value)
    assertFalse(staged(1).exists())
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
    assertFalse(staged(2).exists())
  }

  @Test
  fun downloadsSkipStorageThatsOffButStillFindGamesOnIt() = runTest {
    fillLibrary()
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
    val gba = file("ROMs/gba/Game (USA).gba").apply { parentFile.mkdirs() }
    gba.writeBytes(game)
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
  fun refusesAGameThatDoesNotFit() = runTest {
    fillLibrary()
    val downloads = manager(backgroundScope, server(sizeOverride = { 1L shl 60 }))

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
    val engine = server(installs = installs)
    manager(realTime(), engine, deviceId = "dev-1")

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
    val downloads = manager(realTime(), engine, deviceId = "dev-1")
    waitFor { downloads.progress.first { it[1L]?.bytesDownloaded == 65_536L } }

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
    val engine = server(sizeOverride = { if (fits) null else 1L shl 60 }, installs = installs)
    val romm = MutableStateFlow<RommState>(account(engine, deviceId = "dev-1"))
    val downloads = manager(realTime(), engine, romm = romm)
    waitFor { downloads.queue.first { it.firstOrNull()?.state == DownloadState.Failed } }

    // Sent again from the web page once there's room, and taken at the next sign-in.
    sent = "req-2"
    fits = true
    romm.value = account(engine, deviceId = "dev-1")
    waitFor { while (updates.none { it.first == "req-2" }) delay(10) }

    assertContentEquals(game, file("ROMs/gba/Game (USA).gba").readBytes())
    assertEquals(listOf("req-2" to """{"status":"done"}"""), updates.filter { it.first == "req-2" })
  }

  @Test
  fun pollsForTheGamesSentToTheDeviceEveryMinuteWhileSignedIn() = runTest {
    // Each poll's path, and when it came in virtual time.
    val polls = MutableStateFlow(emptyList<Pair<String, Long>>())
    val canceled = CompletableDeferred<Unit>()
    val engine =
      server(
        installs = { request ->
          if (!request.apiPath.endsWith("/installs")) null
          else {
            polls.update { it + (request.apiPath to testScheduler.currentTime) }
            when (polls.value.size) {
              // A poll that fails waits for the next one.
              1 -> respondError(HttpStatusCode.InternalServerError)
              2 ->
                try {
                  awaitCancellation()
                } finally {
                  canceled.complete(Unit)
                }
              else -> json("[]")
            }
          }
        },
      )
    // A token pasted from the server's web page is no device's, so nothing polls for it.
    val romm = MutableStateFlow<RommState>(account(engine))
    manager(backgroundScope, engine, romm = romm)
    advanceTimeBy(5.minutes)

    romm.value = account(engine, deviceId = "dev-1")
    val start = testScheduler.currentTime
    assertEquals(
      listOf("devices/dev-1/installs" to start, "devices/dev-1/installs" to start + 60_000),
      waitFor { polls.first { it.size >= 2 } },
    )

    romm.value = RommState.SignedOut
    waitFor { canceled.await() }

    romm.value = account(engine, deviceId = "dev-1")
    waitFor { polls.first { it.size >= 3 } }
  }

  @Test
  fun waitsForStorageAccessInsteadOfFailing() = runTest {
    fillLibrary()
    val access = MutableStateFlow(false)
    val withoutAccess = Collections.synchronizedList(mutableListOf<String>())
    val engine =
      server(
        content = { request, bytes ->
          if (!access.value) withoutAccess += request.apiPath
          serve(request, bytes)
        },
      )
    val downloads = manager(backgroundScope, engine, canWrite = access)
    val seen = statuses(downloads, gameRom)
    waitFor { seen.first { it.isNotEmpty() } }

    assertTrue(downloads.enqueue(gameRom))
    access.value = true
    downloads.awaitEmpty()

    val target = file("ROMs/gba/Game (USA).gba")
    assertContentEquals(game, target.readBytes())
    assertDownloaded(seen.value, game.size.toLong(), target)
    assertEquals(emptyList(), withoutAccess)
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
      downloads.progress.first { it[1L]?.bytesDownloaded == 65_536L }
    }

    // The screen that canceled goes away while the cancel waits for the download to stop.
    val caller =
      launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { downloads.cancel(1) }
    caller.cancel()

    waitFor { downloads.queue.first { it.isEmpty() } }
    assertFalse(staged(1).exists())
  }
}
