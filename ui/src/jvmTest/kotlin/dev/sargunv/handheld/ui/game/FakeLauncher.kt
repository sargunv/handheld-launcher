package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Plays nothing, but records what it was asked, and says "Played". It asks [conflict] first when
 * given, and then about [stillRunning] when given. [canCloseEmulators] stands in for a device with
 * a root service.
 */
internal class FakeLauncher(
  private val conflict: SaveConflict? = null,
  private val stillRunning: String? = null,
  override val canCloseEmulators: Boolean = false,
  /** Holds each launch, saying it's getting the core, until it completes. */
  private val hold: CompletableDeferred<Unit>? = null,
  private val keptSaves: List<KeptSave> = emptyList(),
  /** Hears each launch as it starts. */
  private val onPlay: () -> Unit = {},
) : GameLauncher {
  /** Completes once a launch starts. */
  val started = CompletableDeferred<Unit>()
  var canceled = false
  val games = mutableListOf<LibraryGame>()
  val played = mutableListOf<String>()
  val rules = mutableListOf<LaunchRules>()
  var chosen: SaveCopy? = null
  var stillRunningChoice: StillRunningChoice? = null
  val closeEmulators = mutableListOf<Boolean>()

  override suspend fun opensIn(
    game: LibraryGame,
    rules: LaunchRules,
    collections: Set<CollectionId>,
  ) = "Opens in Test Emulator"

  override suspend fun play(
    game: LibraryGame,
    path: String,
    rules: LaunchRules,
    collections: Set<CollectionId>,
    onProgress: (String) -> Unit,
    closeEmulators: Boolean,
    askStillRunning: suspend (StillRunning) -> StillRunningChoice,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
  ): List<String> {
    onPlay()
    started.complete(Unit)
    if (hold != null) {
      onProgress("Downloading core 40%")
      try {
        hold.await()
      } catch (e: CancellationException) {
        canceled = true
        throw e
      }
    }
    if (conflict != null) {
      chosen = chooseSave(conflict) ?: return emptyList()
    }
    val close = closeEmulators && canCloseEmulators
    this.closeEmulators += close
    if (stillRunning != null) {
      val choice = askStillRunning(StillRunning(game.title, stillRunning, close))
      stillRunningChoice = choice
      if (choice == StillRunningChoice.QuitFirst) return emptyList()
    }
    games += game
    played += path
    this.rules += rules
    return listOf("Played")
  }

  override suspend fun keptSaves(game: LibraryGame) = keptSaves
}
