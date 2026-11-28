package dev.sargunv.handheld.launch

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.ui.play.CompanionSync
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a game in [SessionActivity] ended. */
sealed interface SessionEnd {
  data class StartFailed(val reason: String) : SessionEnd

  /** [saveError] says why the save memory couldn't be written, or is null when it was. */
  data class Stopped(val saveError: String?) : SessionEnd
}

/** A play that isn't over: its launch or game, or once [gameOver], what follows the game. */
data class PendingPlay(val game: GameKey, val title: String, val gameOver: Boolean = false)

/**
 * Owns each play, from the start of its launch until its notes are in, and lets one launch or game
 * be under way at a time. The launch runs in its caller, so a caller that stops stops it. Once the
 * game starts, the game and what follows it, sending the save and gathering the notes, run in
 * [scope], so they finish when the launcher's screen or activity goes away. What follows a game may
 * overlap the next play, of another game.
 *
 * @param closeEmulator closes the standalone emulator with the package name it's given, on the
 *   companion's Quit, and returns whether it's gone.
 * @param launcherInFront whether the launcher is what the first display shows.
 * @param unfinished the games of [pending], which this keeps up to date.
 */
internal class Plays(
  private val scope: CoroutineScope,
  private val saves: PlaySaves,
  private val closeEmulator: suspend (packageName: String) -> Boolean,
  private val launcherInFront: StateFlow<Boolean>,
  private val unfinished: MutableStateFlow<Set<GameKey>>,
) {
  private val lock = Any()

  private val plays = MutableStateFlow(emptyList<PendingPlay>())

  /** The plays that aren't over, oldest first. Only the last can be short of its game over. */
  val pending: StateFlow<List<PendingPlay>> = plays.asStateFlow()

  private val current = MutableStateFlow<GameKey?>(null)

  /** The game whose launch or game is under way, or null. */
  val playing: StateFlow<GameKey?> = current.asStateFlow()

  private val companionSession = MutableStateFlow<PlaySession?>(null)

  /** The game playing in another app, for the companion on the second display, or null. */
  val companion: StateFlow<PlaySession?> = companionSession.asStateFlow()

  private val session = AtomicReference<CompletableDeferred<SessionEnd>?>()

  /** Records how the game in [SessionActivity] ended, for the play that started it. */
  fun sessionEnded(end: SessionEnd) {
    session.getAndSet(null)?.complete(end)
  }

  /**
   * Plays [game], named [title]: runs [launch], which starts the game with [Play.builtIn] or
   * [Play.standalone], and returns its notes. [game] is in [playing] and [unfinished] before this
   * first suspends. While another launch or game is under way, or [game]'s last play isn't over, it
   * doesn't start, and says so.
   */
  suspend fun play(
    game: GameKey,
    title: String,
    launch: suspend Play.() -> List<String>,
  ): List<String> {
    val holder =
      synchronized(lock) {
        val list = plays.value
        (list.find { !it.gameOver } ?: list.find { it.game == game }).also {
          if (it == null) publish(list + PendingPlay(game, title))
        }
      }
    if (holder != null) return listOf("Already playing · ${holder.title}")
    val play = Play(game, title)
    try {
      return play.launch()
    } finally {
      // Once the game has started, the play ends after what follows it.
      if (!play.started) play.end()
    }
  }

  /** Under [lock]: makes [list] the pending plays. */
  private fun publish(list: List<PendingPlay>) {
    plays.value = list
    unfinished.value = list.mapTo(LinkedHashSet()) { it.game }
    current.value = list.lastOrNull()?.takeUnless { it.gameOver }?.game
  }

  /** The play of [game], named [title], which [play] runs. */
  inner class Play internal constructor(private val game: GameKey, private val title: String) {
    internal var started = false

    /**
     * Plays the game in [SessionActivity], which [start] starts, and returns the notes once it has
     * ended and [save], unless null, has synced.
     */
    suspend fun builtIn(save: GameSave?, start: () -> Unit): List<String> {
      val end = CompletableDeferred<SessionEnd>()
      return startGame(
        start = {
          session.set(end)
          start()
          true
        },
      ) { note ->
        when (val result = end.await()) {
          is SessionEnd.StartFailed -> return@startGame note("Launch failed · ${result.reason}")
          is SessionEnd.Stopped ->
            if (result.saveError != null) note("Save write failed · ${result.saveError}")
            else log.i { "Back from the game" }
        }
        gameOver()
        // When a write failed, the file still holds the last whole save.
        if (save != null) saves.afterPlay(save, note)
      }
    }

    /**
     * Plays the game in [packageName], named [appName], which [launch] starts, with [companion] set
     * for it, and returns the notes once the launcher is back in front and [save], unless null, has
     * synced. [canQuit] offers the companion's Quit.
     */
    suspend fun standalone(
      packageName: String,
      appName: String,
      save: GameSave?,
      canQuit: Boolean,
      launch: suspend () -> Boolean,
    ): List<String> {
      val syncState = MutableStateFlow<CompanionSync>(CompanionSync.Idle)
      val quit = AtomicBoolean(false)
      companionSession.value =
        PlaySession(
          title,
          syncState.asStateFlow(),
          sync = { push(save, appName, syncState) },
          quit = if (canQuit) ({ quit.set(true) }) else null,
        )
      return startGame(
        start = {
          var started = false
          try {
            started = launch()
          } finally {
            if (!started) companionSession.value = null
          }
          started
        },
      ) { note ->
        try {
          awaitReturn()
        } finally {
          companionSession.value = null
        }
        if (quit.get()) {
          // The emulator may still be writing its save as it goes to the background.
          delay(1.seconds)
          if (!closeEmulator(packageName)) note("Close failed · $appName")
        }
        gameOver()
        if (save != null) saves.afterPlay(save, note)
      }
    }

    /**
     * Runs [start], and once it has started the game, runs [game] in [scope] and returns its notes.
     * [game] calls [gameOver] once only what follows the game is left. A caller that stops waiting
     * leaves the notes in the log.
     */
    private suspend fun startGame(
      start: suspend () -> Boolean,
      game: suspend (note: (String) -> Unit) -> Unit,
    ): List<String> {
      if (!start()) return emptyList()
      started = true
      return scope
        .async {
          try {
            buildList {
              game { text ->
                add(text)
                log.i { text }
              }
            }
          } finally {
            end()
          }
        }
        .await()
    }

    /** Lets the next play start, while what follows this one's game goes on. */
    private fun gameOver() =
      synchronized(lock) {
        publish(plays.value.map { if (it.game == game) it.copy(gameOver = true) else it })
      }

    internal fun end() = synchronized(lock) { publish(plays.value.filterNot { it.game == game }) }
  }

  /** Sends [save] for the companion's Sync, unless a send from it is under way. */
  private fun push(save: GameSave?, appName: String, state: MutableStateFlow<CompanionSync>) {
    if (state.value == CompanionSync.Syncing) return
    state.value = CompanionSync.Syncing
    scope.launch {
      state.value =
        CompanionSync.Done(
          if (save == null) "Save sync unsupported · $appName" else saves.push(save),
        )
    }
  }

  /**
   * The emulator can't be watched for quitting, so the game counts as over when the launcher is
   * back in front after leaving it.
   */
  private suspend fun awaitReturn() {
    withTimeoutOrNull(10.seconds) { launcherInFront.first { !it } }
    launcherInFront.first { it }
  }
}

private val log = Logger.withTag("Play")
