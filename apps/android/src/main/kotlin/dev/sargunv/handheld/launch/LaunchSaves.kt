package dev.sargunv.handheld.launch

import android.content.Context
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.Ps2CardRoots
import dev.sargunv.handheld.data.saves.SaveFolderRoots
import dev.sargunv.handheld.data.saves.SaveShape
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.SaveFiles
import dev.sargunv.handheld.domain.saves.SaveFolders
import dev.sargunv.handheld.domain.saves.SaveLayout
import dev.sargunv.handheld.domain.saves.SaveLocations
import dev.sargunv.handheld.domain.saves.SaveRoots
import dev.sargunv.handheld.handheldApp
import dev.sargunv.handheld.session.romGameId
import java.io.File

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
 */
fun gameSaveFor(
  context: Context,
  target: LaunchTarget,
  platform: PlatformSlug,
  romId: Long,
  romPath: String,
): SaveLookup {
  val emulator = target.emulator ?: return SaveLookup.NotSupported
  val location = SaveLocations.forEmulator(emulator.id, platform) ?: return SaveLookup.NotSupported
  val volumes = context.handheldApp.storageTargets().map { it.root }
  val storage = volumes.firstOrNull() ?: return SaveLookup.NotSupported
  val roots =
    SaveRoots(
      storage = storage,
      anyStorage = volumes,
      packageName = target.packageName,
      core = target.coreFor(platform),
    )
  SaveFiles.candidates(location, romPath, roots)?.let { paths ->
    return SaveLookup.Found(GameSave(romId, emulator.id.value, SaveShape.Files(paths.map(::File))))
  }
  if (SaveFolders.idKind(location, platform) == null) {
    return when (location.layout) {
      is SaveLayout.SharedMemoryCard,
      is SaveLayout.Ps2MemoryCards -> SaveLookup.SharedCards
      else -> SaveLookup.NotSupported
    }
  }
  val headerPlatform = SessionRomHeaders.headerPlatform(platform) ?: return SaveLookup.NoGameId
  val file = SessionRomHeaders.headerFile(romPath) ?: return SaveLookup.NoGameId
  val id =
    romGameId(headerPlatform, file, switchKeys(storage, target.packageName))
      ?: return SaveLookup.NoGameId
  val saveRoots = SaveFiles.folders(location.paths, roots).map(::File)
  val layout = location.layout
  if (layout is SaveLayout.Ps2MemoryCards) {
    // Named after the game's file, as Argosy names the card it syncs.
    val name = File(romPath).nameWithoutExtension + ".ps2"
    val cards = Ps2CardRoots(saveRoots, id, name, folderCards = layout.folderCards)
    // Every PS2 card emulator syncs the same card, so a save from one plays in another.
    val sharesWith = SaveLocations.ps2Cards.map { it.value }.toSet() - emulator.id.value
    return SaveLookup.Found(
      GameSave(romId, emulator.id.value, SaveShape.Cards(cards), sharesWith = sharesWith),
    )
  }
  val save = SaveFolders.forGame(location, platform, id) ?: return SaveLookup.NoGameId
  return SaveLookup.Found(
    GameSave(romId, emulator.id.value, SaveShape.Folders(SaveFolderRoots(saveRoots, save))),
  )
}

/** The emulator's Switch keys, where the yuzu forks and Ryujinx keep them, if it has them. */
private fun switchKeys(storage: String, packageName: String): String? =
  listOf("keys/prod.keys", "system/prod.keys")
    .map { File(storage, "Android/data/$packageName/files/$it") }
    .firstOrNull { it.isFile }
    ?.path
