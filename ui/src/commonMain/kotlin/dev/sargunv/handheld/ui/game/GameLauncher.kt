package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.LibraryGame
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
   * Where [game] opens by [rules], in words, such as "Opens in ARMSX2", or null when there's
   * nothing to say. [collections] are the collections [game] is in.
   */
  suspend fun opensIn(
    game: LibraryGame,
    rules: LaunchRules,
    collections: Set<CollectionId>,
  ): String?

  /**
   * Plays [game], whose file or folder is at [path], in the app [rules] pick for it, and returns
   * once the player is back from the game, with what they should know about how it went, in plain
   * words. [collections] are the collections [game] is in. A ROM's save syncs with RomM; a local
   * game's stays on the device, since RomM files saves by ROM.
   *
   * @param onProgress hears what the launch is doing before the game starts, such as downloading
   *   the game's core, in a few words.
   * @param closeEmulators whether the player turned on closing emulators: the launcher checks
   *   whether an emulator is running, and offers to close it.
   * @param askStillRunning asks what to do when RomM has a newer save, but the emulator may still
   *   hold the game in memory.
   * @param chooseSave asks which copy of the game's save to keep when both changed since they last
   *   synced. Null cancels the launch and leaves both copies as they are.
   */
  suspend fun play(
    game: LibraryGame,
    path: String,
    rules: LaunchRules,
    collections: Set<CollectionId>,
    onProgress: (String) -> Unit,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String>

  /** The copies of [game]'s saves that save sync kept on this device, newest first. */
  suspend fun keptSaves(game: LibraryGame): List<KeptSave>

  /** A launcher for platforms that can't play games yet. */
  object Unsupported : GameLauncher {
    override suspend fun opensIn(
      game: LibraryGame,
      rules: LaunchRules,
      collections: Set<CollectionId>,
    ): String? = null

    override suspend fun play(
      game: LibraryGame,
      path: String,
      rules: LaunchRules,
      collections: Set<CollectionId>,
      onProgress: (String) -> Unit,
      closeEmulators: Boolean,
      askStillRunning: suspend (StillRunning) -> StillRunningChoice,
      chooseSave: suspend (SaveConflict) -> SaveCopy?,
    ): List<String> = listOf("Can't play on this device")

    override suspend fun keptSaves(game: LibraryGame): List<KeptSave> = emptyList()
  }
}
