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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a game in [SessionActivity] ended. */
sealed interface SessionEnd {
  data class StartFailed(val reason: String) : SessionEnd

  /** [saveError] says why the save memory couldn't be written, or is null when it was. */
  data class Stopped(val saveError: String?) : SessionEnd
}

/**
 * Owns each play, from the start of its launch until its notes are in, and lets one launch or game
 * be under way at a time. The launch runs in its caller, so a caller that stops stops it. Once the
 * game starts, the game and the after-game half, which sends the save and gathers the notes, run in
 * [scope], so they finish when the launcher's screen or activity goes away. An after-game half may
 * overlap the next play, of another game.
 *
 * @param closeEmulator closes the standalone emulator with the package name it's given, on the
 *   companion's Quit, and returns whether it's gone.
 * @param launcherInFront whether the launcher is what the first display shows.
 * @param keepAlive starts the service that keeps the process running while a play is pending.
 */
internal class Plays(
  private val scope: CoroutineScope,
  private val saves: PlaySaves,
  private val closeEmulator: suspend (packageName: String) -> Boolean,
  private val launcherInFront: StateFlow<Boolean>,
  private val keepAlive: () -> Unit,
) {
  private val lock = Any()

  /** Under [lock]: the play whose launch or game is under way, if any. */
  private var current: Play? = null

  /** Under [lock]: the plays in their after-game half, oldest first. */
  private var afterGame = emptyList<Play>()

  private val unfinishedGames = MutableStateFlow(emptySet<GameKey>())

  /** The games whose play isn't over: its launch, game or after-game half is under way. */
  val unfinished: StateFlow<Set<GameKey>> = unfinishedGames.asStateFlow()

  private val currentGame = MutableStateFlow<GameKey?>(null)

  /** The game whose launch or game is under way, or null. */
  val playing: StateFlow<GameKey?> = currentGame.asStateFlow()

  private val titles = MutableStateFlow(emptyList<String>())

  /** The titles of the plays that aren't over, oldest first. */
  val pending: StateFlow<List<String>> = titles.asStateFlow()

  private val sendCount = MutableStateFlow(0)

  /** How many saves are on their way to RomM, after a game or from the companion's Sync. */
  val sends: StateFlow<Int> = sendCount.asStateFlow()

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
    val play = Play(game, title)
    val holder =
      synchronized(lock) {
        (current ?: afterGame.find { it.game == game }).also {
          if (it == null) {
            current = play
            publish()
          }
        }
      }
    if (holder != null) return listOf("Already playing · ${holder.title}")
    keepAlive()
    try {
      return play.launch()
    } finally {
      // Once the game has started, the play ends with its after-game half.
      if (!play.started) play.end()
    }
  }

  /** Shows what's under [lock] in the flows. */
  private fun publish() {
    val all = afterGame + listOfNotNull(current)
    unfinishedGames.value = all.mapTo(LinkedHashSet()) { it.game }
    titles.value = all.map { it.title }
    currentGame.value = current?.game
  }

  /** The play of [game], named [title], which [play] runs. */
  inner class Play internal constructor(internal val game: GameKey, internal val title: String) {
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
        if (save != null) send { saves.afterPlay(save, note) }
      }
    }

    /**
     * Plays the game in [packageName], named [appName], which [launch] starts after showing the
     * companion it's given, and returns the notes once the launcher is back in front and [save],
     * unless null, has synced. [canQuit] offers the companion's Quit.
     */
    suspend fun standalone(
      packageName: String,
      appName: String,
      save: GameSave?,
      canQuit: Boolean,
      launch: suspend (PlaySession) -> Boolean,
    ): List<String> {
      val syncState = MutableStateFlow<CompanionSync>(CompanionSync.Idle)
      val quit = AtomicBoolean(false)
      val companion =
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
            started = launch(companion)
          } finally {
            if (!started) PlayCompanion.close()
          }
          started
        },
      ) { note ->
        try {
          awaitReturn()
        } finally {
          PlayCompanion.close()
        }
        if (quit.get()) {
          // The emulator may still be writing its save as it goes to the background.
          delay(QUIT_DELAY)
          if (!closeEmulator(packageName)) note("Close failed · $appName")
        }
        gameOver()
        if (save != null) send { saves.afterPlay(save, note) }
      }
    }

    /**
     * Runs [start], and once it has started the game, runs [game] in [scope] and returns its notes.
     * [game] calls [gameOver] once the game is over and only its after-game half is left. A caller
     * that stops waiting leaves the notes in the log.
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

    /** Lets the next play start, while this one's after-game half goes on. */
    private fun gameOver() {
      synchronized(lock) {
        if (current !== this) return
        current = null
        afterGame += this
        publish()
      }
    }

    /** Ends this play. */
    internal fun end() {
      synchronized(lock) {
        if (current === this) current = null
        afterGame -= this
        publish()
      }
    }
  }

  /** Sends [save] for the companion's Sync, unless a send from it is under way. */
  private fun push(save: GameSave?, appName: String, state: MutableStateFlow<CompanionSync>) {
    if (state.value == CompanionSync.Syncing) return
    state.value = CompanionSync.Syncing
    scope.launch {
      state.value =
        CompanionSync.Done(
          if (save == null) "Save sync unsupported · $appName" else send { saves.push(save) },
        )
    }
  }

  /** Runs [block], counted in [sends]. SaveSync sends one save at a time, in the order asked. */
  private suspend fun <T> send(block: suspend () -> T): T {
    sendCount.update { it + 1 }
    try {
      return block()
    } finally {
      sendCount.update { it - 1 }
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

  private companion object {
    val QUIT_DELAY = 1.seconds
  }
}

private val log = Logger.withTag("Play")
