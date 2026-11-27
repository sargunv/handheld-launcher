package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.saves.SaveFolders
import dev.sargunv.handheld.domain.saves.SaveLocations
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SaveSyncTest {
  private val root = Files.createTempDirectory("save-sync-test").toFile()
  private val saves = File(root, "emulator/saves").apply { mkdirs() }
  private val saveFile = File(saves, "Pokemon Black.sav")
  private val game = GameSave(7, "melonds", SaveShape.Files(listOf(saveFile)))

  @AfterTest fun deleteRoot() = check(root.deleteRecursively())

  private var now = Instant.parse("2025-03-01T00:00:00Z")
  private val clock =
    object : Clock {
      override fun now() = now
    }

  /** A save the fake server holds. */
  private class Stored(
    val id: Long,
    val name: String,
    val bytes: ByteArray,
    val slot: String?,
    val emulator: String?,
    val updatedAt: Instant,
    val hash: String? = contentHash(bytes),
  )

  /**
   * A RomM server that keeps the saves uploaded to it, starting with [initial], and serves them
   * like RomM: every upload to a slot is a new save, the slot's newest.
   */
  private inner class FakeRomm(vararg initial: Stored) {
    val stored = initial.toMutableList()
    val uploads = mutableListOf<Upload>()
    private var nextId = 100L

    /** Whether the server refuses uploads, as it does for a read-only token. */
    var refuseUploads = false

    val server = MockServer { request ->
      val path = request.apiPath
      when {
        path == "saves" && request.method == HttpMethod.Get ->
          json(
            stored
              .filter { it.id > 0 && request.url.parameters["rom_id"] == "7" }
              .joinToString(",", "[", "]") { it.json() },
          )
        path == "saves" && request.method == HttpMethod.Post && refuseUploads ->
          respond(
            """{"detail": "Forbidden"}""",
            HttpStatusCode.Forbidden,
            headersOf(HttpHeaders.ContentType, "application/json"),
          )
        path == "saves" && request.method == HttpMethod.Post -> {
          val upload = parseUpload(request)
          uploads += upload
          val save =
            Stored(
              nextId++,
              upload.fileName,
              upload.bytes,
              upload.query["slot"],
              upload.query["emulator"],
              now,
            )
          stored += save
          json(save.json())
        }
        path.startsWith("saves/") && path.endsWith("/content") -> {
          val id = path.removePrefix("saves/").removeSuffix("/content").toLong()
          respond(ByteReadChannel(stored.first { it.id == id }.bytes), HttpStatusCode.OK)
        }
        else -> error("Unexpected request to $path")
      }
    }

    val client = server.client

    fun push(
      name: String,
      bytes: ByteArray,
      at: Instant,
      hash: String? = contentHash(bytes),
      emulator: String = "melonds",
    ): Stored {
      val save = Stored(nextId++, name, bytes, SAVE_SLOT, emulator, at, hash)
      stored += save
      return save
    }
  }

  private fun Stored.json() =
    """
    {"id": $id, "rom_id": 7, "user_id": 1, "file_name": "$name", "file_size_bytes": ${bytes.size},
     "emulator": ${emulator?.let { "\"$it\"" }}, "slot": ${slot?.let { "\"$it\"" }},
     "content_hash": ${hash?.let { "\"$it\"" }}, "download_path": "/api/saves/$id/content",
     "created_at": "$updatedAt", "updated_at": "$updatedAt"}
    """

  private fun sync(server: FakeRomm) =
    SaveSync(
      server.client,
      File(root, "records"),
      File(root, "history"),
      File(root, "staging"),
      clock,
    )

  /** The copies of [game]'s saves the history keeps, as the save history lists them. */
  private fun kept() = keptSaves(File(root, "history"), game.romId)

  /** The bytes of the copy the history kept last. */
  private fun lastKept() = File(kept().first().path).readBytes()

  private val v1 = "first save".encodeToByteArray()
  private val v2 = "second save".encodeToByteArray()
  private val mine = "played on this device".encodeToByteArray()

  /** A server and a device that both hold [v1], already synced. */
  private suspend fun synced(): Pair<FakeRomm, SaveSync> {
    val server = FakeRomm()
    server.push("Pokemon Black.sav", v1, Instant.parse("2025-01-01T00:00:00Z"))
    saveFile.writeBytes(v1)
    val sync = sync(server)
    assertIs<SaveSyncResult.UpToDate>(sync.sync(game))
    return server to sync
  }

  @Test
  fun pullsANewerServerCopy() = runTest {
    val (server, sync) = synced()
    val newer = server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))

    val result = assertIs<SaveSyncResult.Pulled>(sync.sync(game))
    assertEquals(newer.id, result.save.id)
    assertContentEquals(v2, saveFile.readBytes())
    assertContentEquals(v1, assertNotNull(result.previous).readBytes(), "The old save is kept")
    assertEquals(listOf(result.previous!!.path), kept().map { it.path })
    assertTrue(server.uploads.isEmpty())
    assertEquals(listOf("Pokemon Black.sav"), saves.list()!!.toList(), "No temp files are left")
  }

  @Test
  fun pushesAfterALocalChange() = runTest {
    val (server, sync) = synced()
    saveFile.writeBytes(mine)

    val result = assertIs<SaveSyncResult.Pushed>(sync.sync(game))
    val upload = server.uploads.single()
    assertEquals(
      mapOf("rom_id" to "7", "emulator" to "melonds", "slot" to SAVE_SLOT, "overwrite" to "true"),
      upload.query,
    )
    assertEquals("Pokemon Black.sav", upload.fileName)
    assertContentEquals(mine, upload.bytes)
    assertEquals(server.stored.last().id, result.save.id)
    assertTrue(kept().isEmpty(), "Nothing on the device was replaced")

    assertIs<SaveSyncResult.UpToDate>(sync.sync(game), "The push was recorded")
    assertEquals(1, server.uploads.size)
  }

  @Test
  fun aRefusedPushLosesNothingAndPushesAgainNextTime() = runTest {
    val (server, sync) = synced()
    saveFile.writeBytes(mine)
    server.refuseUploads = true

    assertFailsWith<RommException> { sync.sync(game) }
    assertContentEquals(mine, saveFile.readBytes())
    assertTrue(kept().isEmpty())

    server.refuseUploads = false
    assertIs<SaveSyncResult.Pushed>(sync.sync(game))
    assertContentEquals(mine, server.uploads.single().bytes)
  }

  @Test
  fun aPushSendsALocalChange() = runTest {
    val (server, sync) = synced()
    saveFile.writeBytes(mine)

    assertIs<SaveSyncResult.Pushed>(sync.push(game))
    assertContentEquals(mine, server.uploads.single().bytes)
    assertIs<SaveSyncResult.UpToDate>(sync.sync(game), "The push was recorded")
  }

  @Test
  fun aPushLeavesTheDevicesSaveAloneWhenTheServersIsNewer() = runTest {
    val (server, sync) = synced()
    val newer = server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))

    assertEquals(newer.id, assertIs<SaveSyncResult.ServerNewer>(sync.push(game)).server.id)
    assertContentEquals(v1, saveFile.readBytes())
    assertTrue(kept().isEmpty())
    assertTrue(server.uploads.isEmpty())

    saveFile.delete()
    assertIs<SaveSyncResult.ServerNewer>(sync.push(game), "A missing save isn't pulled either")
    assertTrue(!saveFile.exists())

    assertIs<SaveSyncResult.Pulled>(sync.sync(game), "The next sync pulls it")
  }

  @Test
  fun aPushReportsAConflictWithoutWritingAnything() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)

    assertIs<SaveSyncResult.Conflict>(sync.push(game))
    assertContentEquals(mine, saveFile.readBytes())
    assertTrue(server.uploads.isEmpty())
  }

  @Test
  fun listsTheCopiesKeptInHistoryNewestFirst() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    val pulled = assertIs<SaveSyncResult.Pulled>(sync.sync(game))
    now = Instant.parse("2025-03-02T00:00:00Z")
    saveFile.writeBytes(mine)
    server.push("Pokemon Black [2025].sav", v1, Instant.parse("2025-03-01T00:00:00Z"))
    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))
    sync.resolve(game, conflict, SaveCopy.Device)

    val kept = kept()
    assertEquals(
      listOf(
        KeptSave(
          kept.first().path,
          Instant.parse("2025-03-02T00:00:00Z"),
          SaveCopy.Server,
          "melonds",
          v1.size.toLong(),
        ),
        KeptSave(
          pulled.previous!!.path,
          Instant.parse("2025-03-01T00:00:00Z"),
          SaveCopy.Device,
          "melonds",
          v1.size.toLong(),
        ),
      ),
      kept,
    )
    assertContentEquals(v1, File(kept.first().path).readBytes())
    assertEquals(emptyList(), keptSaves(File(root, "history"), romId = 70))
  }

  @Test
  fun doesNothingWhenNothingChanged() = runTest {
    val (server, sync) = synced()
    val before = server.server.requests.size

    assertEquals(SaveSyncResult.UpToDate(saveFile), sync.sync(game))
    val requests = server.server.requests.drop(before)
    assertEquals(listOf("saves"), requests.map { it.apiPath }, "Only the list is read")
    assertContentEquals(v1, saveFile.readBytes())
    assertTrue(kept().isEmpty())
  }

  @Test
  fun reportsAConflictWithoutWritingAnything() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)
    saveFile.setLastModified(Instant.parse("2025-02-15T00:00:00Z").toEpochMilliseconds())

    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))
    assertEquals(SaveCopy.Device, conflict.newer)
    assertEquals(mine.size.toLong(), conflict.device.sizeBytes)
    assertContentEquals(mine, saveFile.readBytes())
    assertTrue(server.uploads.isEmpty())
    assertTrue(kept().isEmpty())
  }

  @Test
  fun keepingTheServerCopyKeepsTheDevicesInHistoryAndOnTheServer() = runTest {
    val (server, sync) = synced()
    val newer = server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)
    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))

    val resolved = sync.resolve(game, conflict, SaveCopy.Server)
    assertContentEquals(v2, saveFile.readBytes())
    assertContentEquals(mine, lastKept())
    assertEquals(1, kept().size, "One copy, not two")
    val upload = server.uploads.single()
    assertNull(upload.query["slot"], "The device's copy is a separate save, outside the slot")
    assertTrue(upload.fileName.startsWith("Pokemon Black (conflict "), upload.fileName)
    assertContentEquals(mine, upload.bytes)
    assertEquals(newer.id, resolved.save.id)
    assertNull(resolved.uploadError)

    assertIs<SaveSyncResult.UpToDate>(sync.sync(game), "The upload didn't become the slot's newest")
  }

  @Test
  fun keepingTheDeviceCopyKeepsTheServersInHistory() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)
    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))

    sync.resolve(game, conflict, SaveCopy.Device)
    assertContentEquals(mine, saveFile.readBytes())
    assertContentEquals(v2, lastKept())
    val upload = server.uploads.single()
    assertEquals(SAVE_SLOT, upload.query["slot"])
    assertContentEquals(mine, upload.bytes)

    assertIs<SaveSyncResult.UpToDate>(sync.sync(game))
  }

  @Test
  fun neverOverwritesASaveItCouldntKeep() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    // The history can't be written: a file sits where its folder goes.
    File(root, "history").writeText("")

    assertFailsWith<IOException> { sync.sync(game) }
    assertContentEquals(v1, saveFile.readBytes())
  }

  @Test
  fun refusesADownloadThatFailsItsHash() = runTest {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"), md5(v1))

    assertFailsWith<IOException> { sync.sync(game) }
    assertContentEquals(v1, saveFile.readBytes())
    assertTrue(kept().isEmpty())
  }

  @Test
  fun aFirstSyncWithBothCopiesDifferentIsAConflict() = runTest {
    val server = FakeRomm()
    server.push("Pokemon Black.sav", v1, Instant.parse("2025-01-01T00:00:00Z"))
    saveFile.writeBytes(mine)

    assertIs<SaveSyncResult.Conflict>(sync(server).sync(game))
    assertContentEquals(mine, saveFile.readBytes())
  }

  @Test
  fun pullsIntoAnExistingFolderOnly() = runTest {
    val server = FakeRomm()
    server.push("Pokemon Black [2025].sav", v1, Instant.parse("2025-01-01T00:00:00Z"))
    val missing = File(root, "not-made-yet/Pokemon Black.sav")
    val sync = sync(server)

    assertEquals(
      SaveSyncResult.NoFolder(listOf(missing.parent)),
      sync.sync(game.copy(shape = SaveShape.Files(listOf(missing)))),
    )
    assertTrue(!missing.parentFile.exists(), "The emulator's folder isn't made for it")

    val pulled =
      assertIs<SaveSyncResult.Pulled>(
        sync.sync(game.copy(shape = SaveShape.Files(listOf(missing, saveFile)))),
      )
    assertEquals(saveFile, pulled.file)
    assertNull(pulled.previous)
    assertContentEquals(v1, saveFile.readBytes())
  }

  @Test
  fun ignoresOtherEmulatorsAndSlots() = runTest {
    val server =
      FakeRomm(
        Stored(1, "a.sav", v1, SAVE_SLOT, "drastic", Instant.parse("2025-01-01T00:00:00Z")),
        Stored(2, "b.sav", v2, null, "melonds", Instant.parse("2025-01-01T00:00:00Z")),
      )
    assertEquals(SaveSyncResult.NoSave, sync(server).sync(game))
  }

  // The built-in session's save memory, as BuiltInPlayer syncs it: one file in the game's folder,
  // beside the session's save states and the backups it keeps.

  private val gameFolder = File(root, "files/saves/7")
  private val saveRam = File(gameFolder, "Pokemon Gaia.srm")
  private val builtIn = builtInGameSave(romId = 7, "mgba_libretro", saveRam)

  /** Writes what a session leaves beside the save memory: states, their pictures and backups. */
  private fun sessionLeavesItsOtherFiles(backup: ByteArray) {
    File(gameFolder, "Pokemon Gaia.state1").writeBytes("a state".encodeToByteArray())
    File(gameFolder, "Pokemon Gaia.state1.png").writeBytes("a picture".encodeToByteArray())
    File(gameFolder, "Pokemon Gaia.srm.1764130000.bak").writeBytes(backup)
  }

  /** A server and a session folder that both hold [v1] as the built-in game's save, synced. */
  private suspend fun builtInSynced(): Pair<FakeRomm, SaveSync> {
    val server = FakeRomm()
    server.push(
      "Pokemon Gaia.srm",
      v1,
      Instant.parse("2025-01-01T00:00:00Z"),
      emulator = "builtin-mgba_libretro",
    )
    gameFolder.mkdirs()
    saveRam.writeBytes(v1)
    val sync = sync(server)
    assertIs<SaveSyncResult.UpToDate>(sync.sync(builtIn))
    return server to sync
  }

  @Test
  fun builtInPullsBeforeLaunchIntoTheGamesFolder() = runTest {
    val server = FakeRomm()
    server.push(
      "Pokemon Gaia.srm",
      v1,
      Instant.parse("2025-01-01T00:00:00Z"),
      emulator = "builtin-mgba_libretro",
    )
    // BuiltInPlayer makes the game's folder before it syncs.
    gameFolder.mkdirs()
    val sync = sync(server)

    val first = assertIs<SaveSyncResult.Pulled>(sync.sync(builtIn))
    assertEquals(saveRam, first.file)
    assertNull(first.previous)
    assertContentEquals(v1, saveRam.readBytes())

    server.push(
      "Pokemon Gaia.srm",
      v2,
      Instant.parse("2025-02-01T00:00:00Z"),
      emulator = "builtin-mgba_libretro",
    )
    val second = assertIs<SaveSyncResult.Pulled>(sync.sync(builtIn))
    assertContentEquals(v2, saveRam.readBytes())
    assertContentEquals(v1, assertNotNull(second.previous).readBytes(), "The old save is kept")
    assertTrue(server.uploads.isEmpty())
  }

  @Test
  fun builtInPushesOnlyTheSaveMemoryAfterTheSession() = runTest {
    val (server, sync) = builtInSynced()
    saveRam.writeBytes(mine)
    sessionLeavesItsOtherFiles(backup = v1)

    val result = assertIs<SaveSyncResult.Pushed>(sync.sync(builtIn))
    assertEquals(saveRam, result.file)
    val upload = server.uploads.single()
    assertEquals(
      mapOf(
        "rom_id" to "7",
        "emulator" to "builtin-mgba_libretro",
        "slot" to SAVE_SLOT,
        "overwrite" to "true",
      ),
      upload.query,
    )
    assertEquals("Pokemon Gaia.srm", upload.fileName)
    assertContentEquals(mine, upload.bytes)
    assertIs<SaveSyncResult.UpToDate>(sync.sync(builtIn), "The push was recorded")
  }

  @Test
  fun builtInDoesNothingWhenNothingChanged() = runTest {
    val (server, sync) = builtInSynced()
    // A load from the quick menu backs the save up, and a state saves, but the save didn't change.
    sessionLeavesItsOtherFiles(backup = mine)
    val before = server.server.requests.size

    assertEquals(SaveSyncResult.UpToDate(saveRam), sync.sync(builtIn))
    val requests = server.server.requests.drop(before)
    assertEquals(listOf("saves"), requests.map { it.apiPath }, "Only the list is read")
    assertContentEquals(v1, saveRam.readBytes())
  }

  @Test
  fun builtInAsksWhenBothCopiesChanged() = runTest {
    val (server, sync) = builtInSynced()
    val newer =
      server.push(
        "Pokemon Gaia.srm",
        v2,
        Instant.parse("2025-02-01T00:00:00Z"),
        emulator = "builtin-mgba_libretro",
      )
    saveRam.writeBytes(mine)

    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(builtIn))
    assertEquals(saveRam, conflict.device.file)
    assertEquals(newer.id, conflict.server.id)
    assertContentEquals(mine, saveRam.readBytes(), "Nothing is written before the player chooses")
    assertTrue(server.uploads.isEmpty())

    sync.resolve(builtIn, conflict, SaveCopy.Server)
    assertContentEquals(v2, saveRam.readBytes())
    assertContentEquals(mine, lastKept(), "The losing copy is in the history")
    assertEquals(listOf("builtin"), kept().map { it.emulator })
  }

  @Test
  fun builtInSwitchingCoresNeverPushesOverAnotherCoresSave() = runTest {
    val (server, sync) = builtInSynced()
    val gpsp = builtInGameSave(romId = 7, "gpsp_libretro", saveRam)

    assertIs<SaveSyncResult.UpToDate>(sync.sync(gpsp), "The other core's save isn't sent as is")
    assertTrue(server.uploads.isEmpty())

    saveRam.writeBytes(mine)
    assertIs<SaveSyncResult.Pushed>(sync.sync(gpsp))
    assertEquals("builtin-gpsp_libretro", server.uploads.single().query["emulator"])

    // Back on the first core, its save on the server comes back, and the other core's is kept.
    val back = assertIs<SaveSyncResult.Pulled>(sync.sync(builtIn))
    assertContentEquals(v1, saveRam.readBytes())
    assertContentEquals(mine, assertNotNull(back.previous).readBytes())
    assertEquals(1, server.uploads.size, "Nothing went to the first core's saves")
  }

  @Test
  fun builtInIgnoresTheBackupsBesideTheSave() = runTest {
    val server = FakeRomm()
    gameFolder.mkdirs()
    sessionLeavesItsOtherFiles(backup = mine)
    val sync = sync(server)

    assertEquals(SaveSyncResult.NoSave, sync.sync(builtIn), "A backup alone isn't a save")
    assertTrue(server.uploads.isEmpty())

    val (synced, syncedSync) = builtInSynced()
    File(gameFolder, "Pokemon Gaia.srm.1764130001.bak").apply {
      writeBytes(v2)
      setLastModified(saveRam.lastModified() + 60_000)
    }
    assertIs<SaveSyncResult.UpToDate>(syncedSync.sync(builtIn), "A newer backup isn't a change")
    assertTrue(synced.uploads.isEmpty())
  }

  @Test
  fun builtInIgnoresStandaloneEmulatorsSaves() = runTest {
    val server =
      FakeRomm(
        Stored(1, "a.srm", v1, SAVE_SLOT, "retroarch", Instant.parse("2025-01-01T00:00:00Z")),
        Stored(2, "b.srm", v2, SAVE_SLOT, "mgba_libretro", Instant.parse("2025-01-01T00:00:00Z")),
      )
    gameFolder.mkdirs()
    assertEquals(SaveSyncResult.NoSave, sync(server).sync(builtIn))
  }

  // The emulator that keeps the save, as LaunchedEmulators would report it.
  private val notRunning = EmulatorState.NotRunning("test")
  private val holdsThisGame = EmulatorState.MayBeRunning(7, "test")
  private val holdsAnotherGame = EmulatorState.MayBeRunning(8, "test")

  /**
   * [SaveSync.beforeLaunch], answering the prompts with [choice] and [keep], and recording them.
   */
  private class Prompts(
    val choice: StillRunningChoice? = null,
    val keep: SaveCopy? = null,
    val closes: Boolean = true,
  ) {
    val asked = mutableListOf<Boolean>()
    var closed = 0

    private suspend fun close(): Boolean {
      closed++
      return closes
    }

    suspend fun run(sync: SaveSync, game: GameSave, emulator: EmulatorState, canClose: Boolean) =
      sync.beforeLaunch(
        game,
        StandaloneEmulator(
          emulator,
          askStillRunning = {
            asked += it
            choice ?: error("Asked about the emulator")
          },
          close = if (canClose) ::close else null,
        ),
        chooseSave = { keep },
      )
  }

  /** A server with a newer save [v2] than the device's [v1]. */
  private suspend fun serverNewer(): Pair<FakeRomm, SaveSync> {
    val (server, sync) = synced()
    server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    return server to sync
  }

  @Test
  fun aPullWhileTheEmulatorMayRunReportsTheReplacedCopyComingBackAsAConflict() = runTest {
    val (server, sync) = serverNewer()
    assertIs<SaveSyncResult.Pulled>(sync.sync(game, emulatorMayRun = true))

    // The emulator writes the save it held in memory over the pulled one.
    saveFile.writeBytes(v1)
    val pushed = assertIs<SaveSyncResult.Conflict>(sync.push(game))
    assertTrue(pushed.reverted)
    val synced = assertIs<SaveSyncResult.Conflict>(sync.sync(game))
    assertTrue(synced.reverted)
    assertTrue(server.uploads.isEmpty(), "The old copy isn't sent as the newest save")
    assertContentEquals(v1, saveFile.readBytes(), "Nothing on the device changed")

    // Keeping RomM's copy brings the pulled save back, and the device's goes to the history.
    sync.resolve(game, synced, SaveCopy.Server)
    assertContentEquals(v2, saveFile.readBytes())
  }

  @Test
  fun aPullWhileTheEmulatorMayRunStillSendsANewSave() = runTest {
    val (server, sync) = serverNewer()
    assertIs<SaveSyncResult.Pulled>(sync.sync(game, emulatorMayRun = true))

    saveFile.writeBytes(mine)
    assertIs<SaveSyncResult.Pushed>(sync.push(game))
    assertContentEquals(mine, server.uploads.single().bytes)

    // The push settles it: the old copy coming back later is a change like any other.
    saveFile.writeBytes(v1)
    assertIs<SaveSyncResult.Pushed>(sync.sync(game))
  }

  @Test
  fun aPullWhenTheEmulatorIsStoppedDoesntWatchForTheOldCopy() = runTest {
    val (server, sync) = serverNewer()
    assertIs<SaveSyncResult.Pulled>(sync.sync(game))

    saveFile.writeBytes(v1)
    assertIs<SaveSyncResult.Pushed>(sync.push(game))
    assertContentEquals(v1, server.uploads.single().bytes)
  }

  @Test
  fun beforeLaunchAsksBeforePullingWhileTheEmulatorHoldsTheGame() = runTest {
    for (canClose in listOf(false, true)) {
      val (server, sync) = serverNewer()
      val prompts = Prompts(StillRunningChoice.QuitFirst)

      assertEquals(BeforeLaunch.QuitFirst, prompts.run(sync, game, holdsThisGame, canClose))
      assertEquals(listOf(canClose), prompts.asked)
      assertEquals(0, prompts.closed)
      assertContentEquals(v1, saveFile.readBytes())
      assertTrue(kept().isEmpty())
      assertTrue(server.uploads.isEmpty())
      root.resolve("records").deleteRecursively()
    }
  }

  @Test
  fun beforeLaunchPlaysTheDevicesSaveWhenThePlayerChoosesIt() = runTest {
    val (server, sync) = serverNewer()
    val prompts = Prompts(StillRunningChoice.PlayDeviceSave)

    val result = assertIs<BeforeLaunch.Skipped>(prompts.run(sync, game, holdsThisGame, false))
    assertIs<SaveSyncResult.ServerNewer>(result.result)
    assertContentEquals(v1, saveFile.readBytes())
    assertTrue(server.uploads.isEmpty())

    // RomM's save stays newer, so the next launch asks again.
    val again = Prompts(StillRunningChoice.QuitFirst)
    assertEquals(BeforeLaunch.QuitFirst, again.run(sync, game, holdsThisGame, false))
  }

  @Test
  fun beforeLaunchClosesTheEmulatorThenPulls() = runTest {
    val (_, sync) = serverNewer()
    val prompts = Prompts(StillRunningChoice.CloseNow)

    val result = assertIs<BeforeLaunch.Synced>(prompts.run(sync, game, holdsThisGame, true))
    assertIs<SaveSyncResult.Pulled>(result.result)
    assertEquals(1, prompts.closed)
    assertContentEquals(v2, saveFile.readBytes())

    // The emulator was closed first, so the old copy coming back is a change like any other.
    saveFile.writeBytes(v1)
    assertIs<SaveSyncResult.Pushed>(sync.push(game))
  }

  @Test
  fun beforeLaunchPullsNothingWhenTheEmulatorWontClose() = runTest {
    val (server, sync) = serverNewer()
    val prompts = Prompts(StillRunningChoice.CloseNow, closes = false)

    assertEquals(BeforeLaunch.CloseFailed, prompts.run(sync, game, holdsThisGame, true))
    assertContentEquals(v1, saveFile.readBytes())
    assertTrue(server.uploads.isEmpty())

    // Closing isn't offered, so a choice to close does nothing.
    val notOffered = Prompts(StillRunningChoice.CloseNow)
    assertEquals(BeforeLaunch.CloseFailed, notOffered.run(sync, game, holdsThisGame, false))
    assertEquals(0, notOffered.closed)
    assertContentEquals(v1, saveFile.readBytes())
  }

  @Test
  fun beforeLaunchPullsWithoutAskingWhenTheEmulatorDoesntHoldTheGame() = runTest {
    for (emulator in listOf(notRunning, holdsAnotherGame)) {
      val (_, sync) = serverNewer()
      val prompts = Prompts()

      val result = assertIs<BeforeLaunch.Synced>(prompts.run(sync, game, emulator, true))
      assertIs<SaveSyncResult.Pulled>(result.result, "$emulator")
      assertContentEquals(v2, saveFile.readBytes())

      // A pull while the emulator may run watches for the old copy coming back.
      saveFile.writeBytes(v1)
      val after = sync.push(game)
      if (emulator == notRunning) {
        assertIs<SaveSyncResult.Pushed>(after)
      } else {
        assertTrue(assertIs<SaveSyncResult.Conflict>(after).reverted, "$emulator")
      }
      root.resolve("records").deleteRecursively()
      root.resolve("history").deleteRecursively()
    }
  }

  @Test
  fun beforeLaunchSendsTheDevicesSaveWithoutAskingWhileTheEmulatorHoldsTheGame() = runTest {
    val (server, sync) = synced()
    saveFile.writeBytes(mine)

    val result = assertIs<BeforeLaunch.Synced>(Prompts().run(sync, game, holdsThisGame, true))
    assertIs<SaveSyncResult.Pushed>(result.result)
    assertContentEquals(mine, server.uploads.single().bytes)
  }

  @Test
  fun beforeLaunchAsksAboutTheEmulatorOnlyWhenAConflictKeepsRommsCopy() = runTest {
    // Keeping the device's copy writes nothing to the device, so there's nothing to ask.
    run {
      val (server, sync) = serverNewer()
      saveFile.writeBytes(mine)
      val prompts = Prompts(keep = SaveCopy.Device)
      val result = assertIs<BeforeLaunch.Synced>(prompts.run(sync, game, holdsThisGame, true))
      assertIs<SaveSyncResult.Resolved>(result.result)
      assertContentEquals(mine, saveFile.readBytes())
      assertContentEquals(mine, server.uploads.single().bytes)
    }
    root.resolve("records").deleteRecursively()
    root.resolve("history").deleteRecursively()
    run {
      val (_, sync) = serverNewer()
      saveFile.writeBytes(mine)
      val prompts = Prompts(StillRunningChoice.PlayDeviceSave, keep = SaveCopy.Server)
      val result = assertIs<BeforeLaunch.Skipped>(prompts.run(sync, game, holdsThisGame, true))
      assertIs<SaveSyncResult.Conflict>(result.result)
      assertEquals(listOf(true), prompts.asked)
      assertContentEquals(mine, saveFile.readBytes(), "Both copies stay as they were")
      assertTrue(kept().isEmpty())
    }
  }

  @Test
  fun beforeLaunchCancelsAtTheConflictWithoutAskingAboutTheEmulator() = runTest {
    val (_, sync) = serverNewer()
    saveFile.writeBytes(mine)
    val prompts = Prompts(keep = null)

    assertEquals(BeforeLaunch.Canceled, prompts.run(sync, game, holdsThisGame, true))
    assertTrue(prompts.asked.isEmpty())
    assertContentEquals(mine, saveFile.readBytes())
  }

  // Saves kept as folders

  private val ppsspp =
    SaveFolders.forGame(
      assertNotNull(SaveLocations.forEmulator(EmulatorId("ppsspp"), PlatformCatalog.PSP)),
      PlatformCatalog.PSP,
      "ULUS10064",
    )!!
  private val savedata = File(root, "memstick/PSP/SAVEDATA").apply { mkdirs() }
  private val psp =
    GameSave(7, "ppsspp", SaveShape.Folders(SaveFolderRoots(listOf(savedata), ppsspp)))

  /** A PSP PARAM.SFO: a save's carries the savedata keys, installed game data's doesn't. */
  private fun sfo(save: Boolean) =
    ("\u0000PSF" + if (save) "SAVEDATA_FILE_LIST SAVEDATA_PARAMS" else "DISC_ID TITLE")
      .encodeToByteArray()

  private fun File.put(path: String, text: String) =
    File(this, path).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  private fun zip(vararg entries: Pair<String, String?>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
      for ((name, text) in entries) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        text?.let { zip.write(it.encodeToByteArray()) }
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun entries(bytes: ByteArray): Map<String, String> {
    val found = linkedMapOf<String, String>()
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        found[entry.name] = zip.readBytes().decodeToString()
      }
    }
    return found
  }

  /** Every file under [dir], by relative path, with its text. */
  private fun tree(dir: File): Map<String, String> =
    dir
      .walkTopDown()
      .filter { it.isFile }
      .associate {
        it.relativeTo(dir).invariantSeparatorsPath to it.readText()
      }

  /** A PSP game's two save folders, beside its installed data and another game's save. */
  private fun pspFolders() {
    File(savedata, "ULUS10064DATA00/PARAM.SFO").apply { parentFile.mkdirs() }.writeBytes(sfo(true))
    savedata.put("ULUS10064DATA00/DATA.BIN", "progress")
    File(savedata, "ULUS10064SETTINGS/PARAM.SFO")
      .apply { parentFile.mkdirs() }
      .writeBytes(sfo(true))
    File(savedata, "ULUS10064GAMEDATA/PARAM.SFO")
      .apply { parentFile.mkdirs() }
      .writeBytes(sfo(false))
    savedata.put("ULUS10064GAMEDATA/INSTALL.DAT", "installed")
    savedata.put("ULUS99999DATA00/DATA.BIN", "another game")
  }

  @Test
  fun aGamesSaveFoldersSyncAsOneZip() = runTest {
    pspFolders()
    val server = FakeRomm()
    val sync = sync(server)

    assertIs<SaveSyncResult.Pushed>(sync.sync(psp))
    val upload = server.uploads.single()
    assertEquals("ULUS10064.zip", upload.fileName)
    assertEquals(
      listOf(
        "ULUS10064DATA00/DATA.BIN",
        "ULUS10064DATA00/PARAM.SFO",
        "ULUS10064SETTINGS/PARAM.SFO",
      ),
      entries(upload.bytes).keys.toList(),
      "Only the game's saves go, not its installed data or another game's",
    )
    assertIs<SaveSyncResult.UpToDate>(sync.sync(psp), "RomM's hash of the zip matches the record")

    // A file's time alone isn't a change.
    File(savedata, "ULUS10064DATA00/DATA.BIN").setLastModified(0)
    assertIs<SaveSyncResult.UpToDate>(sync.sync(psp))
    savedata.put("ULUS10064DATA00/DATA.BIN", "more progress")
    assertIs<SaveSyncResult.Pushed>(sync.sync(psp))
    assertEquals("more progress", entries(server.uploads.last().bytes)["ULUS10064DATA00/DATA.BIN"])
  }

  @Test
  fun aPulledZipReplacesTheGamesFoldersAndKeepsTheOldOnesInHistory() = runTest {
    pspFolders()
    val server = FakeRomm()
    val sync = sync(server)
    assertIs<SaveSyncResult.Pushed>(sync.sync(psp))
    val before = tree(savedata)
    server.push(
      "ULUS10064.zip",
      zip("ULUS10064DATA00/" to null, "ULUS10064DATA00/DATA.BIN" to "from another device"),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "ppsspp",
    )

    val result = assertIs<SaveSyncResult.Pulled>(sync.sync(psp))
    assertEquals(
      mapOf(
        "ULUS10064DATA00/DATA.BIN" to "from another device",
        "ULUS10064GAMEDATA/INSTALL.DAT" to "installed",
        "ULUS99999DATA00/DATA.BIN" to "another game",
      ),
      tree(savedata) - "ULUS10064GAMEDATA/PARAM.SFO",
      "The game's folders are RomM's, and nothing else changed",
    )
    val kept = assertNotNull(result.previous)
    assertEquals(
      before.filterKeys { it.startsWith("ULUS10064DATA00") || it.startsWith("ULUS10064SETTINGS") },
      entries(kept.readBytes()),
      "The history has the folders the pull replaced",
    )
    assertTrue(savedata.list()!!.none { it.startsWith(".handheld") }, "No temp folders are left")
    assertIs<SaveSyncResult.UpToDate>(sync.sync(psp))
  }

  @Test
  fun aFolderSaveConflictKeepsTheDevicesCopy() = runTest {
    pspFolders()
    val server = FakeRomm()
    val sync = sync(server)
    assertIs<SaveSyncResult.Pushed>(sync.sync(psp))
    savedata.put("ULUS10064DATA00/DATA.BIN", "played here")
    server.push(
      "ULUS10064.zip",
      zip("ULUS10064DATA00/DATA.BIN" to "played there"),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "ppsspp",
    )

    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(psp))
    assertEquals("played here", File(savedata, "ULUS10064DATA00/DATA.BIN").readText())
    sync.resolve(psp, conflict, SaveCopy.Server)
    assertEquals("played there", File(savedata, "ULUS10064DATA00/DATA.BIN").readText())
    assertEquals("played here", entries(lastKept())["ULUS10064DATA00/DATA.BIN"])
    val upload = server.uploads.last()
    assertTrue(upload.fileName.startsWith("ULUS10064 (conflict "), upload.fileName)
    assertEquals("played here", entries(upload.bytes)["ULUS10064DATA00/DATA.BIN"])
    assertIs<SaveSyncResult.UpToDate>(sync.sync(psp))
  }

  @Test
  fun aPullKeepsWhatTheEmulatorWroteAfterTheHistoryCopy() {
    pspFolders()
    val unit = psp.unit()
    val snapshot = assertNotNull(unit.read())
    // The emulator writes after the history copy was taken, before the swap.
    savedata.put("ULUS10064DATA00/DATA.BIN", "written in the gap")
    val kept = mutableListOf<LocalCopy>()
    unit.write(snapshot.file, zip("ULUS10064DATA00/DATA.BIN" to "from RomM"), snapshot.hash) {
      kept += it
    }
    assertEquals("from RomM", File(savedata, "ULUS10064DATA00/DATA.BIN").readText())
    assertEquals("written in the gap", entries(kept.single().bytes)["ULUS10064DATA00/DATA.BIN"])

    // Nothing to keep when the history already has what was replaced.
    val now = assertNotNull(unit.read())
    kept.clear()
    unit.write(now.file, zip("ULUS10064DATA00/DATA.BIN" to "again"), now.hash) { kept += it }
    assertTrue(kept.isEmpty())
  }

  @Test
  fun refusesAZipThatReachesPastTheGamesSave() = runTest {
    for (name in listOf("ULUS99999DATA00/DATA.BIN", "../outside.bin", "ULUS10064GAMEDATA/X")) {
      root.resolve("records").deleteRecursively()
      savedata.deleteRecursively()
      pspFolders()
      val before = tree(savedata)
      val server = FakeRomm()
      server.push(
        "ULUS10064.zip",
        zip(name to "bad"),
        Instant.parse("2025-04-01T00:00:00Z"),
        emulator = "ppsspp",
      )
      val sync = sync(server)
      // Both copies are new to each other, so keep the server's.
      val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(psp))
      assertFailsWith<IOException>(name) { sync.resolve(psp, conflict, SaveCopy.Server) }
      assertEquals(before, tree(savedata), "Nothing changed for $name")
      assertTrue(savedata.list()!!.none { it.startsWith(".handheld") })
    }
  }

  @Test
  fun pullsA3dsSaveIntoFoldersTheEmulatorHasntMadeYet() = runTest {
    val location =
      assertNotNull(SaveLocations.forEmulator(EmulatorId("azahar"), PlatformCatalog.N3DS))
    val sdmc = File(root, "azahar/sdmc/Nintendo 3DS").apply { mkdirs() }
    val save = SaveFolders.forGame(location, PlatformCatalog.N3DS, "0004000000033500")!!
    val game = GameSave(7, "azahar", SaveShape.Folders(SaveFolderRoots(listOf(sdmc), save)))
    val server = FakeRomm()
    server.push(
      "0004000000033500.zip",
      zip(
        "00040000/00033500/data/00000001/main" to "picross",
      ),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "azahar",
    )

    assertIs<SaveSyncResult.Pulled>(sync(server).sync(game))
    val ids = "00000000000000000000000000000000/00000000000000000000000000000000"
    assertEquals(
      mapOf(
        "$ids/title/00040000/00033500/data/00000001/main" to "picross",
      ),
      tree(sdmc),
    )
  }

  @Test
  fun aSwitchSaveIsTheProfilesAndTheDevicesButNotTheSizeFile() = runTest {
    val location =
      assertNotNull(SaveLocations.forEmulator(EmulatorId("eden"), PlatformCatalog.SWITCH))
    val nand = File(root, "eden/nand/user/save").apply { mkdirs() }
    val title = "0100ABCD12345000"
    val profile = "0000000000000000/125D2DBAEBDEB11000296E1E1ECBF401"
    nand.put("$profile/$title/.yuzu_save_size", "size")
    nand.put("$profile/$title/main", "account save")
    nand.put("$profile/0100000000010000/main", "another game")
    nand.put("0000000000000000/00000000000000000000000000000000/$title/shared", "device save")
    val save = SaveFolders.forGame(location, PlatformCatalog.SWITCH, title)!!
    val game = GameSave(7, "eden", SaveShape.Folders(SaveFolderRoots(listOf(nand), save)))
    val server = FakeRomm()
    val sync = sync(server)

    assertIs<SaveSyncResult.Pushed>(sync.sync(game))
    assertEquals(
      mapOf("$title/main" to "account save", "device/$title/shared" to "device save"),
      entries(server.uploads.single().bytes),
    )

    server.push(
      "$title.zip",
      zip("$title/main" to "newer", "device/$title/shared" to "newer too"),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "eden",
    )
    assertIs<SaveSyncResult.Pulled>(sync.sync(game))
    assertEquals("newer", File(nand, "$profile/$title/main").readText())
    assertEquals("size", File(nand, "$profile/$title/.yuzu_save_size").readText())
    assertEquals("another game", File(nand, "$profile/0100000000010000/main").readText())
  }

  @Test
  fun aProfileWithOnlyTheSizeFileDoesntHoldTheSwitchSave() = runTest {
    val location =
      assertNotNull(SaveLocations.forEmulator(EmulatorId("eden"), PlatformCatalog.SWITCH))
    val nand = File(root, "eden/nand/user/save").apply { mkdirs() }
    val title = "0100ABCD12345000"
    nand.put("0000000000000000/125D2DBAEBDEB11000296E1E1ECBF401/$title/main", "account save")
    nand.put("0000000000000000/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/$title/.yuzu_save_size", "size")
    val save = SaveFolders.forGame(location, PlatformCatalog.SWITCH, title)!!
    val game = GameSave(7, "eden", SaveShape.Folders(SaveFolderRoots(listOf(nand), save)))
    val server = FakeRomm()

    assertIs<SaveSyncResult.Pushed>(sync(server).sync(game))
    assertEquals(
      mapOf("$title/main" to "account save"),
      entries(server.uploads.single().bytes),
    )
  }

  @Test
  fun aSwitchDeviceSaveSyncsWithoutAnAccountProfile() = runTest {
    val location =
      assertNotNull(SaveLocations.forEmulator(EmulatorId("eden"), PlatformCatalog.SWITCH))
    val nand = File(root, "eden/nand/user/save").apply { mkdirs() }
    val title = "0100ABCD12345000"
    nand.put("0000000000000000/00000000000000000000000000000000/$title/shared", "device save")
    val save = SaveFolders.forGame(location, PlatformCatalog.SWITCH, title)!!
    val game = GameSave(7, "eden", SaveShape.Folders(SaveFolderRoots(listOf(nand), save)))
    val server = FakeRomm()

    assertIs<SaveSyncResult.Pushed>(sync(server).sync(game))
    assertEquals(
      mapOf("device/$title/shared" to "device save"),
      entries(server.uploads.single().bytes),
    )
  }

  @Test
  fun aGameCubeSaveIsTheGamesGciFiles() = runTest {
    val location =
      assertNotNull(SaveLocations.forEmulator(EmulatorId("dolphin"), PlatformCatalog.GAMECUBE))
    val gc = File(root, "dolphin/GC").apply { mkdirs() }
    gc.put("USA/Card A/01-GZLE-gczelda.gci", "zelda")
    gc.put("USA/Card A/8P-GSNE-sonic.gci", "sonic")
    val save = SaveFolders.forGame(location, PlatformCatalog.GAMECUBE, "GZLE")!!
    val game = GameSave(7, "dolphin", SaveShape.Folders(SaveFolderRoots(listOf(gc), save)))
    val server = FakeRomm()

    assertIs<SaveSyncResult.Pushed>(sync(server).sync(game))
    assertEquals(mapOf("01-GZLE-gczelda.gci" to "zelda"), entries(server.uploads.single().bytes))
  }

  // PS2

  @Test
  fun beforeLaunchAsksBeforePullingToCardsTheEmulatorHoldsForAnotherGame() = runTest {
    val server = FakeRomm()
    val cards = File(root, "armsx2/memcards").apply { mkdirs() }
    val ps2 = GameSave(7, "armsx2", SaveShape.Cards(Ps2CardRoots(listOf(cards), JAK, "Jak.ps2")))
    server.push(
      "Jak.ps2",
      sigilUnit(JAK),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "armsx2",
    )
    val prompts = Prompts(StillRunningChoice.QuitFirst)

    assertEquals(BeforeLaunch.QuitFirst, prompts.run(sync(server), ps2, holdsAnotherGame, false))
    assertEquals(listOf(false), prompts.asked)
    assertTrue(cards.list()!!.isEmpty(), "Nothing was written to the cards")
  }

  // ARMSX2's save of Jak comes down for PocketGOAL, and PocketGOAL's goes up under its own name.
  @Test
  fun aPs2SavePullsFromAnotherCardEmulator() = runTest {
    val server = FakeRomm()
    server.push(
      "Jak.ps2",
      sigilUnit(JAK),
      Instant.parse("2025-04-01T00:00:00Z"),
      emulator = "armsx2",
    )
    val cards = File(root, "pocketgoal/memcards").apply { mkdirs() }
    val jak =
      GameSave(
        7,
        "pocketgoal",
        SaveShape.Cards(Ps2CardRoots(listOf(cards), JAK, "Jak.ps2", folderCards = true)),
        sharesWith = setOf("armsx2"),
      )
    val sync = sync(server)

    assertIs<SaveSyncResult.Pulled>(sync.sync(jak))
    assertTrue(File(cards, "Mcd001.ps2").list()!!.any { it.startsWith("BASCUS-97124") })
    assertIs<SaveSyncResult.UpToDate>(sync.sync(jak))
    assertTrue(server.uploads.isEmpty(), "Nothing went up")
  }

  @Test
  fun aPs2GameSyncsAsOneCardBetweenAFileCardAndAFolderCard() = runTest {
    val server = FakeRomm()
    // This device keeps a file card, holding Ace Combat's saves and another game's.
    val fileCards = File(root, "armsx2/memcards").apply { mkdirs() }
    val fileCard = File(fileCards, "mcd001.ps2")
    fileCard(
      fileCard,
      packed("BASLUS-20152AC04"),
      packed("BESLES-12345OTHER"),
      packed("BASLUS-20152SYS"),
    )
    val name = "Ace Combat 04.ps2"
    val here =
      GameSave(7, "armsx2", SaveShape.Cards(Ps2CardRoots(listOf(fileCards), ACE_COMBAT, name)))
    // Another device keeps a folder card, holding other games' saves.
    val folderCards = File(root, "other/memcards").apply { mkdirs() }
    val folderCard = File(folderCards, "Mcd001.ps2")
    val others = folderCardFiles.filterKeys { !it.startsWith("BASLUS-20152") }
    writeTree(folderCard, others)
    val there =
      here.copy(shape = SaveShape.Cards(Ps2CardRoots(listOf(folderCards), ACE_COMBAT, name)))
    val thereSync =
      SaveSync(
        server.client,
        File(root, "there/records"),
        File(root, "there/history"),
        File(root, "there/staging"),
        clock,
      )
    val sync = sync(server)

    assertIs<SaveSyncResult.Pushed>(sync.sync(here))
    val upload = server.uploads.single()
    assertEquals(name, upload.fileName)
    assertTrue(sigilUnit(ACE_COMBAT).contentEquals(upload.bytes), "The unit sigil would send")
    assertIs<SaveSyncResult.UpToDate>(sync.sync(here))

    now = Instant.parse("2025-03-02T00:00:00Z")
    val pulled = assertIs<SaveSyncResult.Pulled>(thereSync.sync(there))
    assertNull(pulled.previous, "The other device had none of the game's saves")
    val tree = readTree(folderCard)
    val game = folderCardFiles.filterKeys { it.startsWith("BASLUS-20152") }
    for ((path, data) in game.filterKeys { !it.endsWith("_pcsx2_index") }) {
      assertTrue(data.contentEquals(tree[path]), path)
    }
    for ((path, data) in others) assertTrue(data.contentEquals(tree[path]), "$path is as it was")
    assertIs<SaveSyncResult.UpToDate>(thereSync.sync(there))

    // The other device plays, and this one pulls what it saved, keeping its own copy.
    File(folderCard, "BASLUS-20152AC04/AC04.DAT").writeText("played there")
    now = Instant.parse("2025-03-03T00:00:00Z")
    assertIs<SaveSyncResult.Pushed>(thereSync.sync(there))
    val back = assertIs<SaveSyncResult.Pulled>(sync.sync(here))
    assertTrue(sigilUnit(ACE_COMBAT).contentEquals(assertNotNull(back.previous).readBytes()))
    val card = Ps2Card.load(fileCard.readBytes())
    assertTrue(card.holds(packed("BESLES-12345OTHER")), "Another game's save is as it was")
    val ace = card.list().entries.single { it.name == "BASLUS-20152AC04" }
    val data = card.extract(ace.firstCluster).files.single { entryName(it.entry) == "AC04.DAT" }
    assertEquals("played there", data.data.decodeToString())
    assertIs<SaveSyncResult.UpToDate>(sync.sync(here))
  }
}

/** An upload the fake server received. */
private class Upload(val query: Map<String, String>, val fileName: String, val bytes: ByteArray)

private fun md5(bytes: ByteArray): String =
  MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

/** The query and the `saveFile` part of a multipart save upload. */
private suspend fun parseUpload(request: HttpRequestData): Upload {
  val body = request.body.toByteArray()
  val boundary = request.body.contentType!!.parameter("boundary")!!
  val text = body.toString(Charsets.ISO_8859_1)
  val part = text.split("--$boundary").first { "name=\"saveFile\"" in it || "name=saveFile" in it }
  val headers = part.substringBefore("\r\n\r\n")
  val fileName = Regex("filename=\"?([^\"\\r\\n;]+)\"?").find(headers)!!.groupValues[1]
  val content = part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
  return Upload(
    request.url.parameters.entries().associate { it.key to it.value.single() },
    fileName,
    content.toByteArray(Charsets.ISO_8859_1),
  )
}
