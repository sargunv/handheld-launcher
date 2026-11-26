package dev.sargunv.handheld.launch

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.SaveFiles
import dev.sargunv.handheld.domain.saves.SaveLocations
import dev.sargunv.handheld.domain.saves.SaveRoots
import java.io.File
import java.security.MessageDigest

/**
 * The save of the game at [romPath], the RomM ROM [romId], as [target] keeps it for [platform].
 * Null when the emulator isn't known, its save folder isn't, or its saves aren't one file per game,
 * which is all the launcher syncs so far.
 *
 * [folders] stands in for the emulator's own save folders, for testing.
 */
fun gameSaveFor(
  context: Context,
  target: LaunchTarget,
  platform: PlatformSlug,
  romId: Long,
  romPath: String,
  folders: List<String>? = null,
): GameSave? {
  val emulator = target.emulator ?: return null
  val location = SaveLocations.forEmulator(emulator.id, platform) ?: return null
  val storage = Environment.getExternalStorageDirectory().path
  val volumes =
    context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull {
      it.directory?.path
    }
  val roots =
    SaveRoots(
      storage = storage,
      anyStorage = listOf(storage) + (volumes - storage),
      packageName = target.packageName,
      core = target.coreFor(platform),
    )
  val paths = SaveFiles.candidates(location, romPath, roots) ?: return null
  val files =
    if (folders == null) {
      paths.map(::File)
    } else {
      val names = paths.map { it.substringAfterLast('/') }.distinct()
      folders.flatMap { folder -> names.map { File(folder, it) } }
    }
  return GameSave(romId, emulator.id.value, files)
}

/**
 * The launcher's [SaveSync] for [client], keeping its records and history in the app's files. The
 * records are kept apart per [account] (the server and the sign-in), so one account's record never
 * decides what another account's save replaces.
 */
fun saveSync(context: Context, client: RommClient, account: String): SaveSync =
  SaveSync(
    client,
    records = File(context.filesDir, "save-sync/${accountKey(account)}"),
    history = File(context.filesDir, "save-history"),
    staging = File(context.cacheDir, "save-staging"),
  )

private fun accountKey(account: String): String =
  MessageDigest.getInstance("SHA-256").digest(account.encodeToByteArray()).take(8).joinToString(
    "",
  ) {
    "%02x".format(it)
  }
