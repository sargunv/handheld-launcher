package dev.sargunv.handheld.launch

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.saves.GameSave
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
 * Owns each play once its game starts: it waits for the game to end, closes the emulator on the
 * companion's Quit, sends the save and gathers the notes, in [scope], so they finish when the
 * launcher's screen or activity goes away.
 *
 * @param launcherInFront whether the launcher is what the first display shows.
 * @param keepAlive starts the service that keeps the process running while a play is pending.
 */
internal class Plays(
  private val scope: CoroutineScope,
  private val saves: PlaySaves,
  private val emulators: Emulators,
  private val launcherInFront: StateFlow<Boolean>,
  private val keepAlive: () -> Unit,
) {
  private val titles = MutableStateFlow(emptyList<String>())

  /** The titles of the plays whose game or after-game half is under way, oldest first. */
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
   * Plays a game in [SessionActivity], which [start] starts, and returns the notes once it has
   * ended and [save], unless null, has synced.
   */
  suspend fun builtIn(
    title: String,
    save: GameSave?,
    start: () -> Unit,
  ): List<String> {
    val end = CompletableDeferred<SessionEnd>()
    return play(
      title,
      start = {
        session.set(end)
        start()
        true
      },
    ) { note ->
      when (val result = end.await()) {
        is SessionEnd.StartFailed -> return@play note("Launch failed · ${result.reason}")
        is SessionEnd.Stopped ->
          if (result.saveError != null) note("Save write failed · ${result.saveError}")
          else log.i { "Back from the game" }
      }
      // When a write failed, the file still holds the last whole save.
      if (save != null) send { saves.afterPlay(save, note) }
    }
  }

  /**
   * Plays a game in [packageName], named [appName], which [launch] starts after showing the
   * companion it's given, and returns the notes once the launcher is back in front and [save],
   * unless null, has synced. [canQuit] offers the companion's Quit.
   */
  suspend fun standalone(
    title: String,
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
    return play(
      title,
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
        if (!emulators.close(packageName)) note("Close failed · $appName")
      }
      if (save != null) send { saves.afterPlay(save, note) }
    }
  }

  /**
   * Runs [start] while [title]'s play counts as pending, and once it has started the game, runs
   * [afterGame] in [scope] and returns its notes. A caller that stops waiting leaves the notes in
   * the log.
   */
  private suspend fun play(
    title: String,
    start: suspend () -> Boolean,
    afterGame: suspend (note: (String) -> Unit) -> Unit,
  ): List<String> {
    titles.update { it + title }
    keepAlive()
    var started = false
    try {
      started = start()
    } finally {
      if (!started) titles.update { it - title }
    }
    if (!started) return emptyList()
    return scope
      .async {
        try {
          buildList {
            afterGame { text ->
              add(text)
              log.i { text }
            }
          }
        } finally {
          titles.update { it - title }
        }
      }
      .await()
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
