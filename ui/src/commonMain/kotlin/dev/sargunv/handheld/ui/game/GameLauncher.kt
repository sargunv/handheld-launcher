package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning

/** Plays games. Each platform supplies its own, since starting another app is platform-specific. */
interface GameLauncher {
  /**
   * Whether this device lets the launcher close emulators that are running in the background, which
   * the player can turn on with [play]'s `closeEmulators`.
   */
  val canCloseEmulators: Boolean
    get() = false

  /**
   * Where [rom] opens by [rules], in words, such as "Opens in ARMSX2", or null when there's nothing
   * to say. [collections] are the collections [rom] is in.
   */
  suspend fun opensIn(rom: Rom, rules: LaunchRules, collections: Set<CollectionId>): String?

  /**
   * Plays [rom], whose file or folder is at [path], in the app [rules] pick for it, and returns
   * once the player is back from the game, with what they should know about how it went, in plain
   * words. [collections] are the collections [rom] is in.
   *
   * @param onProgress hears what the launch is doing before the game starts, such as downloading
   *   the game's core, in a few words.
   * @param closeEmulators whether the player turned on closing emulators, where
   *   [canCloseEmulators]: the launcher checks whether an emulator is running, and offers to close
   *   it.
   * @param askStillRunning asks what to do when RomM has a newer save, but the emulator may still
   *   hold the game in memory.
   * @param chooseSave asks which copy of the game's save to keep when both changed since they last
   *   synced. Null cancels the launch and leaves both copies as they are.
   */
  suspend fun play(
    rom: Rom,
    path: String,
    rules: LaunchRules,
    collections: Set<CollectionId>,
    onProgress: (String) -> Unit = {},
    closeEmulators: Boolean = false,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice = {
      StillRunningChoice.QuitFirst
    },
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String>

  /** The copies of [rom]'s saves that save sync kept on this device, newest first. */
  suspend fun keptSaves(rom: Rom): List<KeptSave>

  /** A launcher for platforms that can't play games yet. */
  object Unsupported : GameLauncher {
    override suspend fun opensIn(
      rom: Rom,
      rules: LaunchRules,
      collections: Set<CollectionId>,
    ): String? = null

    override suspend fun play(
      rom: Rom,
      path: String,
      rules: LaunchRules,
      collections: Set<CollectionId>,
      onProgress: (String) -> Unit,
      closeEmulators: Boolean,
      askStillRunning: suspend (StillRunning) -> StillRunningChoice,
      chooseSave: suspend (SaveConflict) -> SaveCopy?,
    ): List<String> = listOf("Playing games isn't available on this device yet.")

    override suspend fun keptSaves(rom: Rom): List<KeptSave> = emptyList()
  }
}
