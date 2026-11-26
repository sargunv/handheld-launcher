package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.saves.SaveConflict

/** Plays games. Each platform supplies its own, since starting another app is platform-specific. */
interface GameLauncher {
  /**
   * Where [rom] opens, in words, such as "Opens in ARMSX2", or null when there's nothing to say.
   */
  suspend fun opensIn(rom: Rom): String?

  /**
   * Plays [rom], whose file or folder is at [path], and returns once the player is back from the
   * game, with what they should know about how it went, in plain words.
   *
   * @param chooseSave asks which copy of the game's save to keep when both changed since they last
   *   synced. Null cancels the launch and leaves both copies as they are.
   */
  suspend fun play(
    rom: Rom,
    path: String,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String>

  /** The copies of [rom]'s saves that save sync kept on this device, newest first. */
  suspend fun keptSaves(rom: Rom): List<KeptSave>

  /** A launcher for platforms that can't play games yet. */
  object Unsupported : GameLauncher {
    override suspend fun opensIn(rom: Rom): String? = null

    override suspend fun play(
      rom: Rom,
      path: String,
      chooseSave: suspend (SaveConflict) -> SaveCopy?,
    ): List<String> = listOf("Playing games isn't available on this device yet.")

    override suspend fun keptSaves(rom: Rom): List<KeptSave> = emptyList()
  }
}
