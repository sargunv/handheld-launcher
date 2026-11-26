package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.cores.JsonFile
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSave
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/** The slot the launcher's saves pair on between devices. Argosy's default saves use it too. */
const val SAVE_SLOT = "autosave"

/**
 * A game's save on this device.
 *
 * @param emulator the emulator, as the server files the game's saves under it.
 * @param candidates where the save file can be, most likely first.
 */
data class GameSave(val romId: Long, val emulator: String, val candidates: List<File>) {
  internal val key: String
    get() = "$romId-${emulator.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
}

/** The save file on this device, as it was when the conflict was found. */
data class DeviceSave(val file: File, val sizeBytes: Long, val modifiedAt: Instant)

/** What a [SaveSync] call did. */
sealed interface SaveSyncResult {
  /** Neither the device nor the server has a save for the game. */
  data object NoSave : SaveSyncResult

  /** Neither copy changed since the last sync, or both hold the same bytes. */
  data class UpToDate(val file: File?) : SaveSyncResult

  /**
   * The server's save replaced the device's at [file]. [previous] is where the device's old save,
   * if it had one, is kept in the history.
   */
  data class Pulled(val file: File, val save: RommSave, val previous: File?) : SaveSyncResult

  /** The device's save at [file] went to the server as [save]. */
  data class Pushed(val file: File, val save: RommSave) : SaveSyncResult

  /**
   * Both copies changed since the last sync, so the player has to choose one with
   * [SaveSync.resolve]. Nothing was written.
   */
  data class Conflict(val device: DeviceSave, val server: RommSave) : SaveSyncResult {
    /** The copy changed last, which the conflict screen offers first. */
    val newer: SaveCopy
      get() = if (device.modifiedAt > server.updatedAt) SaveCopy.Device else SaveCopy.Server
  }

  /**
   * The server has a save to pull, but none of [folders] exists and can be written: the emulator
   * hasn't made its save folder yet, or the device keeps other apps' folders from the launcher.
   */
  data class NoFolder(val folders: List<String>) : SaveSyncResult

  /**
   * The player chose [kept] in a conflict, which is now the save at [file] and the slot's newest
   * save [save] on the server. The other copy is kept in the history at [history], and on the
   * server as [otherOnServer]: the server's old save when the device's copy won, or a separate
   * upload when the server's won. That upload can fail, which [uploadError] says; the history still
   * has the copy.
   */
  data class Resolved(
    val kept: SaveCopy,
    val file: File,
    val save: RommSave,
    val history: File,
    val otherOnServer: RommSave?,
    val uploadError: String? = null,
  ) : SaveSyncResult
}

/**
 * Syncs games' saves with a RomM server, one file per game.
 *
 * It keeps a record per game of what it last synced, in [records], so it can tell which copies
 * changed since: a save on the device changed when its hash differs from the record's, and the
 * server's when its newest save in [SAVE_SLOT] isn't the one recorded. It never replaces a save
 * file on the device without first copying it into [history], and it never deletes one.
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
   * @throws RommException when the server can't be reached or refuses a call.
   * @throws IOException when a save file can't be read or written.
   */
  suspend fun sync(game: GameSave): SaveSyncResult = mutex.withLock {
    withContext(Dispatchers.IO) {
      val record = recordFile(game).read()
      val local = game.candidates.firstOrNull { it.isFile }
      val localBytes = local?.readBytes()
      val localHash = localBytes?.let(::md5)
      val server = latestOnServer(game)
      val localChanged = localHash != null && localHash != record?.localHash
      val serverChanged = server != null && !record.matches(server)
      when {
        local == null && server == null -> SaveSyncResult.NoSave
        // A missing save comes back from the server, since there's nothing to overwrite.
        local == null -> pull(game, server!!, null)
        !localChanged && !serverChanged -> SaveSyncResult.UpToDate(local)
        !localChanged -> pull(game, server!!, local)
        !serverChanged -> push(game, local!!, localBytes!!)
        localHash == server!!.contentHash -> {
          record(game, local!!, localHash!!, server)
          SaveSyncResult.UpToDate(local)
        }
        else ->
          SaveSyncResult.Conflict(
            DeviceSave(
              local!!,
              localBytes!!.size.toLong(),
              Instant.fromEpochMilliseconds(local.lastModified()),
            ),
            server,
          )
      }
    }
  }

  /**
   * Settles [conflict] by keeping the [keep] copy. The other copy goes to the history first, and to
   * the server as a separate save when it's the device's.
   *
   * @throws RommException when the server can't be reached or refuses the kept copy.
   * @throws IOException when a save file can't be read or written.
   */
  suspend fun resolve(
    game: GameSave,
    conflict: SaveSyncResult.Conflict,
    keep: SaveCopy,
  ): SaveSyncResult.Resolved = mutex.withLock {
    withContext(Dispatchers.IO) {
      val file = conflict.device.file
      when (keep) {
        SaveCopy.Device -> {
          val serverBytes = download(conflict.server)
          val kept = keepInHistory(game, SaveCopy.Server, conflict.server.fileName, serverBytes)
          val bytes = file.readBytes()
          val pushed = upload(game, file.name, bytes, SAVE_SLOT)
          record(game, file, md5(bytes), pushed)
          SaveSyncResult.Resolved(keep, file, pushed, kept, conflict.server)
        }
        SaveCopy.Server -> {
          val bytes = file.readBytes()
          val kept = keepInHistory(game, SaveCopy.Device, file.name, bytes)
          // A name of its own, so the upload doesn't replace an earlier one.
          val stamp = clock.now().toString().replace(':', '-')
          val name = "${file.nameWithoutExtension} (conflict $stamp).${file.extension}"
          var uploadError: String? = null
          val uploaded =
            try {
              upload(game, name, bytes, slot = null)
            } catch (e: RommException) {
              uploadError = e.message
              null
            }
          val serverBytes = download(conflict.server)
          replace(game, file, serverBytes, keptAs = kept)
          record(game, file, md5(serverBytes), conflict.server)
          SaveSyncResult.Resolved(keep, file, conflict.server, kept, uploaded, uploadError)
        }
      }
    }
  }

  /** The newest save in [SAVE_SLOT] the server has from [game]'s emulator. */
  private suspend fun latestOnServer(game: GameSave): RommSave? =
    client
      .saves(romId = game.romId)
      .filter { it.slot == SAVE_SLOT && it.emulator == game.emulator }
      .maxWithOrNull(compareBy({ it.updatedAt }, { it.id }))

  private suspend fun pull(game: GameSave, server: RommSave, local: File?): SaveSyncResult {
    val target = local ?: pullTarget(game, server) ?: return noFolder(game)
    val bytes = download(server)
    val previous = replace(game, target, bytes)
    record(game, target, md5(bytes), server)
    return SaveSyncResult.Pulled(target, server, previous)
  }

  private fun noFolder(game: GameSave) =
    SaveSyncResult.NoFolder(game.candidates.mapNotNull { it.parent }.distinct())

  /**
   * Where a pulled save goes when the device has none: the first candidate in a folder that exists
   * and can be written, preferring the server file's extension. Folders aren't created, because
   * they may belong to the emulator, which has to own them.
   */
  private fun pullTarget(game: GameSave, server: RommSave): File? {
    val usable =
      game.candidates.filter {
        it.parentFile?.let { dir -> dir.isDirectory && dir.canWrite() } == true
      }
    val extension = server.fileName.substringAfterLast('.', "")
    return usable.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
      ?: usable.firstOrNull()
  }

  private suspend fun push(game: GameSave, local: File, bytes: ByteArray): SaveSyncResult {
    val save = upload(game, local.name, bytes, SAVE_SLOT)
    record(game, local, md5(bytes), save)
    return SaveSyncResult.Pushed(local, save)
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
      if (expected != null && !md5(bytes).equals(expected, ignoreCase = true)) {
        throw IOException("The downloaded save doesn't match the server's hash")
      }
      return bytes
    } finally {
      staged.delete()
    }
  }

  /**
   * Writes [bytes] to [target]. A file already there is copied into the history first, unless
   * [keptAs] already holds it, and the history copy is returned.
   */
  private fun replace(game: GameSave, target: File, bytes: ByteArray, keptAs: File? = null): File? {
    val previous =
      when {
        !target.exists() -> null
        keptAs != null && keptAs.readBytes().contentEquals(target.readBytes()) -> keptAs
        else -> keepInHistory(game, SaveCopy.Device, target.name, target.readBytes())
      }
    // Write a sibling and move it over the target, so the emulator never sees half a save.
    val temp = File(target.parentFile, ".${target.name}.handheld-part")
    temp.writeBytes(bytes)
    try {
      Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      temp.delete()
    }
    return previous
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
  ): File {
    val folder = File(history, game.key).apply { mkdirs() }
    val stamp = clock.now().toEpochMilliseconds()
    val prefix = "$stamp-${origin.name.lowercase()}-"
    var file = File(folder, prefix + name)
    var n = 1
    while (file.exists()) file = File(folder, "$prefix${n++}-$name")
    Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC)
      .use { it.write(bytes) }
    if (!file.readBytes().contentEquals(bytes)) {
      throw IOException("Couldn't keep a copy of the save in the history")
    }
    return file
  }

  private fun record(game: GameSave, file: File, localHash: String, server: RommSave) {
    recordFile(game)
      .write(
        SyncRecord(
          path = file.path,
          localHash = localHash,
          serverSaveId = server.id,
          serverHash = server.contentHash,
          serverUpdatedAt = server.updatedAt.toEpochMilliseconds(),
          syncedAt = clock.now().toEpochMilliseconds(),
        ),
      )
  }

  private fun recordFile(game: GameSave) =
    JsonFile(Path(records.path, "${game.key}.json"), SyncRecord.serializer())

  /** What the history holds for [game], oldest first. */
  fun history(game: GameSave): List<File> =
    File(history, game.key).listFiles().orEmpty().sortedBy { it.name }
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
 * What the launcher last synced for a game: the save file at [path] with hash [localHash], and the
 * server's save [serverSaveId], last changed at [serverUpdatedAt]. Times are epoch milliseconds.
 */
@Serializable
internal data class SyncRecord(
  val path: String,
  val localHash: String,
  val serverSaveId: Long,
  val serverHash: String? = null,
  val serverUpdatedAt: Long,
  val syncedAt: Long,
)

private fun SyncRecord?.matches(server: RommSave): Boolean =
  this != null &&
    serverSaveId == server.id &&
    serverUpdatedAt == server.updatedAt.toEpochMilliseconds()

private fun md5(bytes: ByteArray): String =
  MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
