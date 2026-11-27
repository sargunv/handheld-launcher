package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSave
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/** The slot the launcher's saves pair on between devices. Argosy's default saves use it too. */
internal const val SAVE_SLOT = "autosave"

/**
 * A game's save on this device.
 *
 * @param emulator the emulator, as the server files the game's saves under it.
 * @param owner what keeps the save file on this device, which names the game's sync record and
 *   history. It's the emulator, unless several of the server's emulators share one file.
 * @param sharesWith the other emulators whose saves of the game are the same as this one's, so a
 *   pull takes the newest of any of them. Pushes still go under [emulator].
 */
@ConsistentCopyVisibility
data class GameSave
internal constructor(
  val romId: Long,
  val emulator: String,
  internal val shape: SaveShape,
  val owner: String = emulator,
  val sharesWith: Set<String> = emptySet(),
) {
  internal val key: String
    get() = historyKey(romId, owner)
}

/** How an emulator keeps a game's save on this device. */
internal sealed interface SaveShape {
  /** One file, at the first of [candidates] that exists, most likely first. */
  data class Files(val candidates: List<File>) : SaveShape

  /** Folders or several files, which sync as one zip. */
  data class Folders(val roots: SaveFolderRoots) : SaveShape

  /** Folders on the emulator's shared PS2 memory cards, which sync as one per-game card. */
  data class Cards(val roots: Ps2CardRoots) : SaveShape
}

/** What names the sync record and history of the ROM [romId]'s save that [owner] keeps. */
private fun historyKey(romId: Long, owner: String): String =
  "$romId-${owner.replace(UnsafeKeyChars, "_")}"

private val UnsafeKeyChars = Regex("[^A-Za-z0-9._-]")

/**
 * The save memory at [saveRam] of the ROM [romId], as the built-in session keeps it when the core
 * [coreLibrary] runs the game. Only that file syncs: the save states beside it stay on this device,
 * and so do the backups the session keeps there as `<name>.<time>.bak`.
 *
 * The server keeps each core's saves apart, but every core uses the game's one file, so the file
 * has one sync record and one history, whichever core ran last. After a switch of cores, the file
 * only goes to the new core's saves once that core changes it, and a switch back pulls the old
 * core's save only after copying the file into the history.
 */
fun builtInGameSave(romId: Long, coreLibrary: String, saveRam: File): GameSave =
  GameSave(
    romId,
    BuiltInSaves.emulatorOf(coreLibrary),
    SaveShape.Files(listOf(saveRam)),
    owner = BUILT_IN_OWNER,
  )

/** What names the ROM [romId]'s built-in saves in the history, whichever core made them. */
internal fun builtInHistoryKey(romId: Long): String = historyKey(romId, BUILT_IN_OWNER)

/** What keeps the built-in session's saves on this device, for [GameSave.owner]. */
private const val BUILT_IN_OWNER = "builtin"

/** The save file on this device, as it was when the conflict was found. */
data class DeviceSave(val file: File, val sizeBytes: Long, val modifiedAt: Instant)

/** What a [SaveSync] call did. */
sealed interface SaveSyncResult {
  /** Neither the device nor the server has a save for the game. */
  data object NoSave : PushResult

  /** Neither copy changed since the last sync, or both hold the same bytes. */
  data class UpToDate(val file: File) : PushResult

  /**
   * The server's save replaced the device's at [file]. [previous] is where the device's old save,
   * if it had one, is kept in the history.
   */
  data class Pulled(val file: File, val save: RommSave, val previous: File?) : SaveSyncResult

  /** The device's save at [file] went to the server as [save]. */
  data class Pushed(val file: File, val save: RommSave) : PushResult

  /**
   * Both copies changed since the last sync, so the player has to choose one with
   * [SaveSync.resolve]. Nothing was written.
   *
   * [reverted] marks the device's copy as the one a pull replaced while the emulator may still have
   * been running: the emulator likely wrote the save it held in memory over the pulled one. It
   * counts as a change on both sides, so it isn't sent as the newest save.
   */
  data class Conflict(
    val device: DeviceSave,
    val server: RommSave,
    val reverted: Boolean = false,
  ) : PushResult

  /**
   * The server has a save to pull, but none of [folders] exists and can be written: the emulator
   * hasn't made its save folder yet, or the device keeps other apps' folders from the launcher.
   */
  data class NoFolder(val folders: List<String>) : SaveSyncResult

  /**
   * The server's save [server] is newer and the device's didn't change, or the device has none, so
   * there's nothing to push. [SaveSync.push] leaves the device's save alone; the next
   * [SaveSync.sync] pulls it.
   */
  data class ServerNewer(val server: RommSave) : PushResult

  /**
   * The player chose [kept] in a conflict, which is now the save at [file] and the slot's newest
   * save [save] on the server. The other copy is kept in the history, and on the server: the
   * server's old save when the device's copy won, or a separate upload when the server's won. That
   * upload can fail, which [uploadError] says; the history still has the copy.
   */
  data class Resolved(
    val kept: SaveCopy,
    val file: File,
    val save: RommSave,
    val uploadError: String? = null,
  ) : SaveSyncResult
}

/** What [SaveSync.push] did, which never writes to the device. */
sealed interface PushResult : SaveSyncResult

/**
 * Syncs games' saves with a RomM server: one file per game, or a game's save folders as one zip.
 *
 * It keeps a record per game of what it last synced, in [records], so it can tell which copies
 * changed since: a save on the device changed when its hash differs from the record's, and the
 * server's when its newest save in [SAVE_SLOT] isn't the one recorded. It never replaces a save on
 * the device without first copying it into [history], and it never deletes one.
 *
 * Calls run one at a time.
 *
 * @param staging where downloads go before they're checked.
 */
class SaveSync(
  private val client: RommClient,
  private val records: File,
  private val history: File,
  private val staging: File,
  private val clock: Clock = Clock.System,
) {
  private val mutex = Mutex()

  /**
   * Brings [game]'s save in step with the server: pulls the server's save when only it changed or
   * the device has none, pushes the device's when only that changed, and reports a
   * [SaveSyncResult.Conflict] when both did.
   *
   * @param emulatorMayRun whether the emulator that keeps the save may still be running, so it
   *   could write the save it holds in memory over a pulled one. A pull then remembers the copy it
   *   replaced, and a later sync that finds that copy back reports a conflict instead of sending
   *   it.
   * @throws RommException when the server can't be reached or refuses a call.
   * @throws IOException when a save file can't be read or written.
   */
  suspend fun sync(game: GameSave, emulatorMayRun: Boolean = false): SaveSyncResult =
    mutex.withLock {
      withContext(Dispatchers.IO) {
        val unit = game.unit()
        val local = unit.read()
        when (val found = settle(game, local)) {
          is SaveSyncResult.ServerNewer -> pull(game, unit, found.server, local, emulatorMayRun)
          else -> found
        }
      }
    }

  /**
   * Like [sync], but never writes to the device: when [sync] would pull, it reports
   * [SaveSyncResult.ServerNewer] instead. For while the game runs, since an emulator holds its save
   * in memory and would write over a pulled one.
   *
   * @throws RommException when the server can't be reached or refuses a call.
   * @throws IOException when the save file can't be read.
   */
  suspend fun push(game: GameSave): PushResult = mutex.withLock {
    withContext(Dispatchers.IO) { settle(game, game.unit().read()) }
  }

  /**
   * Compares [game]'s save [local] with the server's. Pushes it when only it changed, and records
   * it as synced when both hold the same bytes.
   */
  private suspend fun settle(game: GameSave, local: LocalCopy?): PushResult {
    val record = recordFile(game).read()
    val server = latestOnServer(game)
    val localHash = local?.hash
    val localChanged = localHash != null && localHash != record?.localHash
    val serverChanged = server != null && !record.matches(server)
    return when {
      local == null && server == null -> SaveSyncResult.NoSave
      local == null -> SaveSyncResult.ServerNewer(server!!)
      !localChanged && !serverChanged -> SaveSyncResult.UpToDate(local.file)
      !localChanged -> SaveSyncResult.ServerNewer(server!!)
      // The copy a pull replaced came back: the emulator wrote its old save over the pulled one.
      server != null && localHash == record?.replacedHash ->
        SaveSyncResult.Conflict(deviceSave(local), server, reverted = true)
      !serverChanged -> push(game, local)
      localHash == server.contentHash -> {
        record(game, local.hash, server)
        SaveSyncResult.UpToDate(local.file)
      }
      else -> SaveSyncResult.Conflict(deviceSave(local), server)
    }
  }

  private fun deviceSave(local: LocalCopy) =
    DeviceSave(local.file, local.bytes.size.toLong(), local.modifiedAt)

  /**
   * Settles [conflict] by keeping the [keep] copy. The other copy goes to the history first, and to
   * the server as a separate save when it's the device's.
   *
   * @param emulatorMayRun as for [sync], when the server's copy replaces the device's.
   * @throws RommException when the server can't be reached or refuses the kept copy.
   * @throws IOException when a save file can't be read or written.
   */
  suspend fun resolve(
    game: GameSave,
    conflict: SaveSyncResult.Conflict,
    keep: SaveCopy,
    emulatorMayRun: Boolean = false,
  ): SaveSyncResult.Resolved = mutex.withLock {
    withContext(Dispatchers.IO) {
      val unit = game.unit()
      val local = unit.read() ?: throw IOException("Save missing")
      val file = local.file
      when (keep) {
        SaveCopy.Device -> {
          val serverBytes = download(conflict.server)
          keepInHistory(game, SaveCopy.Server, conflict.server.fileName, serverBytes)
          val pushed = upload(game, local.name, local.bytes, SAVE_SLOT)
          record(game, local.hash, pushed)
          SaveSyncResult.Resolved(keep, file, pushed)
        }
        SaveCopy.Server -> {
          val kept = keepInHistory(game, SaveCopy.Device, local.name, local.bytes)
          // A name of its own, so the upload doesn't replace an earlier one.
          val stamp = clock.now().toString().replace(':', '-')
          val base = local.name.substringBeforeLast('.')
          val extension = local.name.substringAfterLast('.', "")
          val name = "$base (conflict $stamp).$extension"
          val uploadError =
            try {
              upload(game, name, local.bytes, slot = null)
              null
            } catch (e: RommException) {
              e.message
            }
          val serverBytes = download(conflict.server)
          val replaced = replace(game, unit, file, serverBytes, keptAs = kept)
          recordPull(game, unit, conflict.server, serverBytes, replaced, emulatorMayRun)
          SaveSyncResult.Resolved(keep, file, conflict.server, uploadError)
        }
      }
    }
  }

  /** The newest save in [SAVE_SLOT] the server has from [game]'s emulator or one it shares with. */
  private suspend fun latestOnServer(game: GameSave): RommSave? =
    client
      .saves(romId = game.romId)
      .filter {
        it.slot == SAVE_SLOT && (it.emulator == game.emulator || it.emulator in game.sharesWith)
      }
      .maxWithOrNull(compareBy({ it.updatedAt }, { it.id }))

  private suspend fun pull(
    game: GameSave,
    unit: SaveUnit,
    server: RommSave,
    local: LocalCopy?,
    emulatorMayRun: Boolean,
  ): SaveSyncResult {
    // When the device has no save, the pull goes where the emulator would keep it, in a folder
    // that exists and can be written.
    val target =
      local?.file ?: unit.target(server.fileName) ?: return SaveSyncResult.NoFolder(unit.folders())
    val bytes = download(server)
    val replaced = replace(game, unit, target, bytes)
    val written = recordPull(game, unit, server, bytes, replaced, emulatorMayRun)
    return SaveSyncResult.Pulled(written?.file ?: target, server, replaced?.history)
  }

  /**
   * Records [server]'s [bytes] as pulled into [unit], and returns the save there now. While the
   * emulator may run, it can write its old save back as soon as the pull is in place, so the record
   * keeps what the pull put there, not what's there now, and the [replaced] copy it may write back.
   */
  private suspend fun recordPull(
    game: GameSave,
    unit: SaveUnit,
    server: RommSave,
    bytes: ByteArray,
    replaced: Replaced?,
    emulatorMayRun: Boolean,
  ): LocalCopy? {
    val written = unit.read()
    if (emulatorMayRun) {
      record(game, contentHash(bytes), server, replaced?.hash)
    } else {
      record(game, written?.hash ?: contentHash(bytes), server)
    }
    return written
  }

  private suspend fun push(game: GameSave, local: LocalCopy): SaveSyncResult.Pushed {
    val save = upload(game, local.name, local.bytes, SAVE_SLOT)
    record(game, local.hash, save)
    return SaveSyncResult.Pushed(local.file, save)
  }

  // A slot upload always becomes the slot's newest save of this emulator, so the record matches the
  // save the next sync finds.
  private suspend fun upload(game: GameSave, name: String, bytes: ByteArray, slot: String?) =
    client.uploadSave(game.romId, name, bytes, game.emulator, slot, overwrite = slot != null)

  /** [save]'s bytes, checked against the hash the server lists. */
  private suspend fun download(save: RommSave): ByteArray {
    staging.mkdirs()
    val staged = Files.createTempFile(staging.toPath(), "${save.id}-", ".part").toFile()
    try {
      client.downloadSave(save.id, Path(staged.path))
      val bytes = staged.readBytes()
      val expected = save.contentHash
      if (
        expected != null &&
          !md5(bytes).equals(expected, ignoreCase = true) &&
          !contentHash(bytes).equals(expected, ignoreCase = true)
      ) {
        throw IOException("Hash mismatch")
      }
      return bytes
    } finally {
      staged.delete()
    }
  }

  /** The device's save a pull replaced: its [hash], and the [history] copy that holds it. */
  private class Replaced(val hash: String, val history: File)

  /**
   * Writes [bytes] in place of [unit]'s save, at [target]. A save already there is copied into the
   * history first, unless [keptAs] already holds it.
   */
  private fun replace(
    game: GameSave,
    unit: SaveUnit,
    target: File,
    bytes: ByteArray,
    keptAs: File? = null,
  ): Replaced? {
    val current = unit.read()
    var replaced =
      when {
        current == null -> null
        keptAs != null && contentHash(keptAs.readBytes()) == current.hash ->
          Replaced(current.hash, keptAs)
        else ->
          Replaced(
            current.hash,
            keepInHistory(game, SaveCopy.Device, current.name, current.bytes),
          )
      }
    // What the emulator wrote after the copy above is what the pull replaced, and may write back.
    unit.write(current?.file ?: target, bytes, current?.hash) { changed ->
      replaced =
        Replaced(changed.hash, keepInHistory(game, SaveCopy.Device, changed.name, changed.bytes))
    }
    return replaced
  }

  /**
   * Keeps [bytes], [origin]'s copy of a save named [name], in [game]'s history, and returns the
   * file. It fails rather than return before the copy is complete.
   */
  private fun keepInHistory(
    game: GameSave,
    origin: SaveCopy,
    name: String,
    bytes: ByteArray,
  ): File = keepInHistory(history, game.key, origin, name, bytes, clock.now())

  private suspend fun record(
    game: GameSave,
    localHash: String,
    server: RommSave,
    replacedHash: String? = null,
  ) {
    recordFile(game)
      .write(
        SyncRecord(
          localHash = localHash,
          serverSaveId = server.id,
          serverUpdatedAt = server.updatedAt.toEpochMilliseconds(),
          replacedHash = replacedHash,
        ),
      )
  }

  private fun recordFile(game: GameSave) =
    JsonFile(Path(records.path, "${game.key}.json"), SyncRecord.serializer())
}

/**
 * Keeps [bytes], [origin]'s copy of a save named [name], in the history folder [history] of a
 * [SaveSync], among the copies of the game and save owner [key] names, as kept at [now]. Returns
 * the file. It fails rather than return before the copy is complete.
 */
internal fun keepInHistory(
  history: File,
  key: String,
  origin: SaveCopy,
  name: String,
  bytes: ByteArray,
  now: Instant,
): File {
  val folder = File(history, key).apply { mkdirs() }
  val prefix = "${now.toEpochMilliseconds()}-${origin.name.lowercase()}-"
  var file = File(folder, prefix + name)
  var n = 1
  while (file.exists()) file = File(folder, "$prefix${n++}-$name")
  Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC).use {
    it.write(bytes)
  }
  if (!file.readBytes().contentEquals(bytes)) {
    throw IOException("History copy failed")
  }
  return file
}

/**
 * The copies of the ROM [romId]'s saves kept in [history], the history folder of a [SaveSync], for
 * every emulator, newest first.
 */
fun keptSaves(history: File, romId: Long): List<KeptSave> =
  history
    .listFiles { file -> file.isDirectory && file.name.startsWith("$romId-") }
    .orEmpty()
    .flatMap { folder ->
      val emulator = folder.name.removePrefix("$romId-")
      folder.listFiles().orEmpty().mapNotNull { file ->
        // Named <epoch millis>-<origin>-<name>, as keepInHistory names them.
        val (stamp, origin) =
          file.name.split('-', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
        KeptSave(
          path = file.path,
          keptAt = Instant.fromEpochMilliseconds(stamp.toLongOrNull() ?: return@mapNotNull null),
          copy = SaveCopy.entries.find { it.name.lowercase() == origin } ?: return@mapNotNull null,
          emulator = emulator,
          sizeBytes = file.length(),
        )
      }
    }
    .sortedByDescending { it.keptAt }

/**
 * What the launcher last synced for a game: the device's save with hash [localHash], and the
 * server's save [serverSaveId], last changed at [serverUpdatedAt] in epoch milliseconds.
 * [replacedHash] is the hash of the copy the last pull replaced, when the emulator may still have
 * been running then.
 */
@Serializable
internal data class SyncRecord(
  val localHash: String,
  val serverSaveId: Long,
  val serverUpdatedAt: Long,
  val replacedHash: String? = null,
)

private fun SyncRecord?.matches(server: RommSave): Boolean =
  this != null &&
    serverSaveId == server.id &&
    serverUpdatedAt == server.updatedAt.toEpochMilliseconds()
