package dev.sargunv.handheld.domain.saves

/**
 * Where the built-in session keeps games' saves: a folder per game under [root], named by the
 * game's id, which holds the game's save memory, its save states and any files its core saves
 * itself. Save memory and states are named after the game's file, as RetroArch names them.
 */
class BuiltInSaves(private val root: String) {
  /** The folder of the game [gameId]. */
  fun folderOf(gameId: Long): String = "${root.trimEnd('/')}/$gameId"

  /** The save memory file of the game [gameId], whose file is at [romPath]. */
  fun saveRamOf(gameId: Long, romPath: String): String = "${pathOf(gameId, romPath)}.srm"

  /**
   * The save-state files of the game [gameId], whose file is at [romPath], one for each of
   * [STATE_SLOTS], in order. A state's picture goes beside it, at [thumbnailOf].
   */
  fun statesOf(gameId: Long, romPath: String): List<String> = STATE_SLOTS.map {
    "${pathOf(gameId, romPath)}.state$it"
  }

  /** The game's file name without its extension, in the game's folder. */
  private fun pathOf(gameId: Long, romPath: String): String {
    val name = romPath.substringAfterLast('/')
    val stem = name.substringBeforeLast('.').ifEmpty { name }
    return "${folderOf(gameId)}/$stem"
  }

  companion object {
    /** The numbers of the save-state slots each game has. */
    val STATE_SLOTS: IntRange = 1..6

    /** The picture of the game as the state at [statePath] was saved. */
    fun thumbnailOf(statePath: String): String = "$statePath.png"
  }
}
