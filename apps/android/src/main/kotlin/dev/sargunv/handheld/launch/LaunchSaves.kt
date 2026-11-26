package dev.sargunv.handheld.launch

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.saves.GameIds
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.Ps2CardRoots
import dev.sargunv.handheld.data.saves.SaveFolderRoots
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.SaveFiles
import dev.sargunv.handheld.domain.saves.SaveFolders
import dev.sargunv.handheld.domain.saves.SaveLayout
import dev.sargunv.handheld.domain.saves.SaveLocations
import dev.sargunv.handheld.domain.saves.SaveRoots
import dev.sargunv.handheld.session.chdDiscRead
import dev.sargunv.handheld.session.zstdPrefix
import java.io.File
import java.security.MessageDigest

/** Where a game's save is on this device, or why it doesn't sync. */
sealed interface SaveLookup {
  data class Found(val save: GameSave) : SaveLookup

  /** The emulator isn't known, its save folder isn't, or the launcher can't sync its layout yet. */
  data object NotSupported : SaveLookup

  /** The emulator keeps every game's saves on a shared memory card the launcher can't read. */
  data object SharedCards : SaveLookup

  /** The emulator files saves by the game's id, which the launcher couldn't read from its file. */
  data object NoGameId : SaveLookup
}

/**
 * The save of the game at [romPath], the RomM ROM [romId], as [target] keeps it for [platform]: a
 * file per game, or folders filed under an id the game's file carries, which reading it finds.
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
): SaveLookup {
  val emulator = target.emulator ?: return SaveLookup.NotSupported
  val location = SaveLocations.forEmulator(emulator.id, platform) ?: return SaveLookup.NotSupported
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
  SaveFiles.candidates(location, romPath, roots)?.let { paths ->
    val files =
      if (folders == null) {
        paths.map(::File)
      } else {
        val names = paths.map { it.substringAfterLast('/') }.distinct()
        folders.flatMap { folder -> names.map { File(folder, it) } }
      }
    return SaveLookup.Found(GameSave(romId, emulator.id.value, files))
  }
  val kind =
    SaveFolders.idKind(location, platform)
      ?: return when (location.layout) {
        is SaveLayout.SharedMemoryCard,
        is SaveLayout.Ps2MemoryCards -> SaveLookup.SharedCards
        else -> SaveLookup.NotSupported
      }
  val id =
    GameIds.read(
      File(romPath),
      kind,
      switchKeys(storage, target.packageName),
      zstd = { file, offset, length -> zstdPrefix(file.path, offset.toULong(), length.toUInt()) },
      chd = { file, offset, length -> chdDiscRead(file.path, offset.toULong(), length.toUInt()) },
    ) ?: return SaveLookup.NoGameId
  val saveRoots = (folders ?: SaveFiles.folders(location.paths, roots)).map(::File)
  val layout = location.layout
  if (layout is SaveLayout.Ps2MemoryCards) {
    // Named after the game's file, as Argosy names the card it syncs.
    val name = File(romPath).nameWithoutExtension + ".ps2"
    val cards = Ps2CardRoots(saveRoots, id, name, folderCards = layout.folderCards)
    // Every PS2 card emulator syncs the same card, so a save from one plays in another.
    val sharesWith = SaveLocations.ps2Cards.map { it.value }.toSet() - emulator.id.value
    return SaveLookup.Found(
      GameSave(romId, emulator.id.value, emptyList(), cards = cards, sharesWith = sharesWith),
    )
  }
  val save = SaveFolders.forGame(location, platform, id) ?: return SaveLookup.NoGameId
  return SaveLookup.Found(
    GameSave(romId, emulator.id.value, emptyList(), folders = SaveFolderRoots(saveRoots, save)),
  )
}

/** The Switch emulator's keys, as the yuzu forks and Ryujinx keep them, if it has them. */
private fun switchKeys(storage: String, packageName: String): File? =
  listOf("keys/prod.keys", "system/prod.keys")
    .map { File(storage, "Android/data/$packageName/files/$it") }
    .firstOrNull { it.isFile }

/**
 * The launcher's [SaveSync] for [client], keeping its records and history in the app's files. The
 * records are kept apart per [account] (the server and the sign-in), so one account's record never
 * decides what another account's save replaces.
 */
fun saveSync(context: Context, client: RommClient, account: String): SaveSync =
  SaveSync(
    client,
    records = File(context.filesDir, "save-sync/${accountKey(account)}"),
    history = saveHistory(context),
    staging = File(context.cacheDir, "save-staging"),
  )

/** Where save sync keeps the copies it replaced, for every account. */
fun saveHistory(context: Context): File = File(context.filesDir, "save-history")

private fun accountKey(account: String): String =
  MessageDigest.getInstance("SHA-256").digest(account.encodeToByteArray()).take(8).joinToString(
    "",
  ) {
    "%02x".format(it)
  }
