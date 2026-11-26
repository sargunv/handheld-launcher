package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.LibretroCores

/**
 * What fills in a [SaveLocation]'s placeholders on one device, for one launch.
 *
 * @param storage the primary external storage root, such as `/storage/emulated/0`.
 * @param anyStorage every mounted storage volume's root, the primary one first.
 * @param packageName the emulator's installed package.
 * @param core the libretro core the game runs in, by library name, for RetroArch.
 */
data class SaveRoots(
  val storage: String,
  val anyStorage: List<String> = listOf(storage),
  val packageName: String,
  val core: String? = null,
)

/** Where a game's save file can be, for the layouts that keep one file per game. */
object SaveFiles {
  /**
   * The paths where [location] could keep the save of the game at [romPath], most likely first:
   * each candidate folder with each of the layout's extensions. A folder that needs the RetroArch
   * core's folder is left out when [SaveRoots.core] isn't a known core.
   *
   * Null when [location] doesn't keep one file per game, such as a folder per game or a shared
   * memory card, or when saves there can't be synced: it needs root, or [SaveLocation.syncable] is
   * false.
   */
  fun candidates(location: SaveLocation, romPath: String, roots: SaveRoots): List<String>? {
    if (location.requiresRoot || !location.syncable) return null
    val name = romPath.substringAfterLast('/').let { it.substringBeforeLast('.', it) }
    val (folders, extensions) =
      when (val layout = location.layout) {
        is SaveLayout.RomNamedFile -> folders(location.paths, roots) to layout.extensions
        is SaveLayout.BesideRom -> listOf(romPath.substringBeforeLast('/', "")) to layout.extensions
        else -> return null
      }
    return folders.flatMap { folder -> extensions.map { "$folder/$name.$it" } }
  }

  private fun folders(paths: List<String>, roots: SaveRoots): List<String> {
    val coreFolder = roots.core?.let { LibretroCores.byLibrary(it)?.retroArchFolder }
    return paths.flatMap { path ->
      if (SavePaths.CORE in path && coreFolder == null) return@flatMap emptyList()
      val filled =
        path
          .replace(SavePaths.STORAGE, roots.storage)
          .replace(SavePaths.PACKAGE, roots.packageName)
          .replace(SavePaths.CORE, coreFolder.orEmpty())
      if (SavePaths.ANY_STORAGE in filled) {
        roots.anyStorage.map { filled.replace(SavePaths.ANY_STORAGE, it) }
      } else {
        listOf(filled)
      }
    }
  }
}
