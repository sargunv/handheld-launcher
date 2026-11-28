package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Plays nothing, but records what it was asked, and says "Played". It asks [conflict] first when
 * given, and then about [stillRunning] when given. [canCloseEmulators] stands in for a device with
 * a root service. Its plays are in [playing] and [plays] while they last.
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
  /** Holds each play's after-game half until it completes, in a scope of its own. */
  private val afterGame: CompletableDeferred<Unit>? = null,
) : GameLauncher {
  private val current = MutableStateFlow<GameKey?>(null)
  override val playing: StateFlow<GameKey?> = current.asStateFlow()

  /** The games whose play isn't over, which the app's data follows once it's launched. */
  var plays = MutableStateFlow(emptySet<GameKey>())

  private val afterGames = CoroutineScope(Dispatchers.Default)

  /** Completes once a launch starts. */
  val started = CompletableDeferred<Unit>()
  var canceled = false
  val games = mutableListOf<LibraryGame>()
  val played = mutableListOf<String>()
  val rules = mutableListOf<LaunchRules>()
  var chosen: SaveCopy? = null
  var stillRunningChoice: StillRunningChoice? = null
  val closeEmulators = mutableListOf<Boolean>()

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
    val key = game.key
    plays.update { it + key }
    current.value = key
    var afterGameStarted = false
    try {
      val notes = launch(game, path, rules, onProgress, closeEmulators, askStillRunning, chooseSave)
      val after = afterGame?.takeIf { notes.isNotEmpty() } ?: return notes
      current.value = null
      afterGameStarted = true
      return afterGames
        .async {
          try {
            after.await()
            notes
          } finally {
            plays.update { it - key }
          }
        }
        .await()
    } finally {
      if (current.value == key) current.value = null
      if (!afterGameStarted) plays.update { it - key }
    }
  }

  private suspend fun launch(
    game: LibraryGame,
    path: String,
    rules: LaunchRules,
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
