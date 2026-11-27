package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.downloads.headerFile
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.SaveFiles
import dev.sargunv.handheld.domain.saves.SaveFolders
import dev.sargunv.handheld.domain.saves.SaveLayout
import dev.sargunv.handheld.domain.saves.SaveLocations
import dev.sargunv.handheld.domain.saves.SaveRoots
import java.io.File

/** Where a game's save is on this device, or why it doesn't sync. */
sealed interface SaveLookup {
  data class Found(val save: GameSave) : SaveLookup

  /**
   * The emulator isn't known, its save folder isn't, or the launcher can't sync its layout yet,
   * such as a memory card every game shares.
   */
  data object NotSupported : SaveLookup

  /** The emulator files saves by the game's id, which the launcher couldn't read from its file. */
  data object NoGameId : SaveLookup
}

/**
 * The save of the game at [romPath], the RomM ROM [romId], as [target] keeps it for [platform] on
 * the shared storage [volumes], the device's own first: a file per game, or folders filed under the
 * id that [gameId] reads from the game's header [file][headerFile], with the emulator's Switch
 * [keys][dev.sargunv.handheld.domain.saves.SaveLocation.keys] when it has them.
 */
fun gameSaveFor(
  target: LaunchTarget,
  platform: PlatformSlug,
  romId: Long,
  romPath: String,
  volumes: List<String>,
  gameId: (file: String, keys: String?) -> String?,
): SaveLookup {
  val emulator = target.emulator ?: return SaveLookup.NotSupported
  val location = SaveLocations.forEmulator(emulator.id, platform) ?: return SaveLookup.NotSupported
  val storage = volumes.firstOrNull() ?: return SaveLookup.NotSupported
  val roots =
    SaveRoots(
      storage = storage,
      anyStorage = volumes,
      packageName = target.packageName,
      core = target.coreFor(platform),
    )
  SaveFiles.candidates(location, romPath, roots)?.let { paths ->
    return SaveLookup.Found(GameSave(romId, emulator.id.value, FileUnit(paths.map(::File))))
  }
  if (SaveFolders.idKind(location, platform) == null) return SaveLookup.NotSupported
  val file = headerFile(romPath) ?: return SaveLookup.NoGameId
  val keys = SaveFiles.folders(location.keys, roots).firstOrNull { File(it).isFile }
  val id = gameId(file, keys) ?: return SaveLookup.NoGameId
  val saveRoots = SaveFiles.folders(location.paths, roots).map(::File)
  val layout = location.layout
  if (layout is SaveLayout.Ps2MemoryCards) {
    // Named after the game's file, as Argosy names the card it syncs.
    val name = File(romPath).nameWithoutExtension + ".ps2"
    val cards = Ps2CardUnit(saveRoots, id, name, folderCards = layout.folderCards)
    // Every PS2 card emulator syncs the same card, so a save from one plays in another.
    val sharesWith = SaveLocations.ps2Cards.map { it.value }.toSet() - emulator.id.value
    return SaveLookup.Found(
      GameSave(romId, emulator.id.value, cards, sharesWith = sharesWith),
    )
  }
  val save = SaveFolders.forGame(location, platform, id) ?: return SaveLookup.NoGameId
  return SaveLookup.Found(GameSave(romId, emulator.id.value, FolderUnit(saveRoots, save)))
}
