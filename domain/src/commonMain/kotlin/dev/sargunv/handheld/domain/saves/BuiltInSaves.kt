package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.GameKey

/**
 * Where the built-in session keeps games' saves: a folder per game under [root], which holds the
 * game's save memory, its save states and any files its core saves itself. A ROM's folder is named
 * by its id. A local game's is its file's whole path under [localRoot], so two files of one name on
 * different storage keep their own saves, and it becomes the ROM's once the file matches one. Save
 * memory and states are named after the game's file, as RetroArch names them.
 */
class BuiltInSaves(root: String) {
  private val root = root.trimEnd('/')

  /** The folder that holds the local games' folders. */
  val localRoot: String = "${this.root}/local"

  /** The folder of [game]. */
  fun folderOf(game: GameKey): String =
    when (game) {
      is GameKey.Romm -> "$root/${game.romId}"
      is GameKey.Local -> "$localRoot/${game.path.trimStart('/')}"
    }

  /**
   * The save memory file of the game whose saves are in [folder] and whose file is at [romPath].
   */
  fun saveRamOf(folder: String, romPath: String): String = "${pathOf(folder, romPath)}.srm"

  /**
   * The save-state files of the game whose saves are in [folder] and whose file is at [romPath],
   * one for each of [STATE_SLOTS], in order. A state's picture goes beside it, at [thumbnailOf].
   */
  fun statesOf(folder: String, romPath: String): List<String> = STATE_SLOTS.map {
    "${pathOf(folder, romPath)}.state$it"
  }

  /** The game's file name without its extension, in the game's folder. */
  private fun pathOf(folder: String, romPath: String): String {
    val name = romPath.substringAfterLast('/')
    val stem = name.substringBeforeLast('.').ifEmpty { name }
    return "$folder/$stem"
  }

  companion object {
    /** The numbers of the save-state slots each game has. */
    val STATE_SLOTS: IntRange = 1..6

    /** The picture of the game as the state at [statePath] was saved. */
    fun thumbnailOf(statePath: String): String = "$statePath.png"

    /**
     * The emulator RomM files the built-in session's saves under, when [coreLibrary] made them,
     * such as `builtin-mgba_libretro`. The prefix keeps them apart from saves a standalone
     * emulator, or RetroArch with the same core, made. A colon would read better, but RomM takes
     * the name as a folder name and refuses one.
     */
    fun emulatorOf(coreLibrary: String): String = "builtin-$coreLibrary"
  }
}
