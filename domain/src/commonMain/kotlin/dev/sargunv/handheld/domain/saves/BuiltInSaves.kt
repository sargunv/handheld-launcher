package dev.sargunv.handheld.domain.saves

/**
 * Where the built-in session keeps games' saves: a folder per game under [root], named by the
 * game's id, which holds the game's save memory and any files its core saves itself. Save memory is
 * named after the game's file, as RetroArch names it.
 */
class BuiltInSaves(private val root: String) {
  /** The folder of the game [gameId]. */
  fun folderOf(gameId: Long): String = "${root.trimEnd('/')}/$gameId"

  /** The save memory file of the game [gameId], whose file is at [romPath]. */
  fun saveRamOf(gameId: Long, romPath: String): String {
    val name = romPath.substringAfterLast('/')
    val stem = name.substringBeforeLast('.').ifEmpty { name }
    return "${folderOf(gameId)}/$stem.srm"
  }
}
