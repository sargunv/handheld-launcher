package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
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
  private val game = GameSave(romId = 7, emulator = "melonds", candidates = listOf(saveFile))

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
    val hash: String? = md5(bytes),
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
      hash: String? = md5(bytes),
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
    assertEquals(listOf(result.previous), sync.history(game))
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
    assertTrue(sync.history(game).isEmpty(), "Nothing on the device was replaced")

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
    assertTrue(sync.history(game).isEmpty())

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
    assertTrue(sync.history(game).isEmpty())
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
    val resolved = sync.resolve(game, conflict, SaveCopy.Device)

    assertEquals(
      listOf(
        KeptSave(
          resolved.history.path,
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
      keptSaves(File(root, "history"), romId = 7),
    )
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
    assertTrue(sync.history(game).isEmpty())
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
    assertTrue(sync.history(game).isEmpty())
  }

  @Test
  fun keepingTheServerCopyKeepsTheDevicesInHistoryAndOnTheServer() = runTest {
    val (server, sync) = synced()
    val newer = server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)
    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))

    val resolved = sync.resolve(game, conflict, SaveCopy.Server)
    assertContentEquals(v2, saveFile.readBytes())
    assertContentEquals(mine, resolved.history.readBytes())
    assertEquals(listOf(resolved.history), sync.history(game), "One copy, not two")
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
    val newer = server.push("Pokemon Black [2025].sav", v2, Instant.parse("2025-02-01T00:00:00Z"))
    saveFile.writeBytes(mine)
    val conflict = assertIs<SaveSyncResult.Conflict>(sync.sync(game))

    val resolved = sync.resolve(game, conflict, SaveCopy.Device)
    assertContentEquals(mine, saveFile.readBytes())
    assertContentEquals(v2, resolved.history.readBytes())
    assertEquals(newer.id, resolved.otherOnServer?.id)
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
    assertTrue(sync.history(game).isEmpty())
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
      sync.sync(game.copy(candidates = listOf(missing))),
    )
    assertTrue(!missing.parentFile.exists(), "The emulator's folder isn't made for it")

    val pulled =
      assertIs<SaveSyncResult.Pulled>(sync.sync(game.copy(candidates = listOf(missing, saveFile))))
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

    val resolved = sync.resolve(builtIn, conflict, SaveCopy.Server)
    assertContentEquals(v2, saveRam.readBytes())
    assertContentEquals(mine, resolved.history.readBytes(), "The losing copy is in the history")
    assertEquals(
      listOf("builtin-mgba_libretro"),
      keptSaves(File(root, "history"), romId = 7).map { it.emulator },
    )
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
