package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.downloads.Removal
import dev.sargunv.handheld.data.downloads.UploadOutcome
import dev.sargunv.handheld.data.downloads.UploadPhase
import dev.sargunv.handheld.data.downloads.UploadResult
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_FOR_ACCOUNT
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_REQUESTED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A [question] waiting for the player's [answer]. */
internal class Pending<Q, A>(val question: Q) {
  val answer = CompletableDeferred<A>()
}

/** The dialog open over the Game screen. */
internal sealed interface GameDialog {
  data object Menu : GameDialog

  data object Remove : GameDialog

  /** Asks to upload, with what the sign-in may do. */
  data class Upload(val access: UploadAccess) : GameDialog

  data object Leave : GameDialog

  /** Offers to sign in again with uploads allowed, saying [reason]. */
  data class AllowUploads(val reason: String) : GameDialog
}

/**
 * What the Game screen does with its game in [scope]: play, remove or upload it. A launch ends the
 * dialog that asks about it. A removal and an upload run in data's scope, so they finish when the
 * screen goes away.
 */
@Stable
internal class GameActions(private val state: AppState, private val scope: CoroutineScope) {
  var dialog: GameDialog? by mutableStateOf(null)

  val playing: Boolean
    get() = state.playing != null

  /** What the launch is doing before the game starts, such as getting its core. */
  var playProgress: String? by mutableStateOf(null)
    private set

  /** What the player should know about the last thing they did here, such as how a play went. */
  var notes by mutableStateOf(emptyList<String>())
    private set

  var saveConflict: Pending<SaveConflict, SaveCopy?>? by mutableStateOf(null)
    private set

  var stillRunning: Pending<StillRunning, StillRunningChoice>? by mutableStateOf(null)
    private set

  /** Whether it's asking what the sign-in may upload, before it asks the player. */
  var checkingUploads by mutableStateOf(false)
    private set

  /** Whether the player removed the local game here, so the screen leaves once its file is gone. */
  var removedLocalGame by mutableStateOf(false)
    private set

  fun play(game: LibraryGame, path: String) {
    notes = emptyList()
    // Undispatched, so the play is under way in [AppState.playing] before this returns.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        notes =
          state.play(game.key) {
            state.launcher.play(
              game,
              path,
              state.settings.launch.toRules(),
              collectionsOf(state, game),
              onProgress = { playProgress = it },
              closeEmulators = state.settings.closeEmulators,
              askStillRunning = { prompt -> ask(prompt) { stillRunning = it } },
            ) { conflict ->
              ask(conflict) { saveConflict = it }
            }
          }
      } finally {
        playProgress = null
        if (dialog == GameDialog.Leave) dialog = null
      }
    }
  }

  /** Asks [question], which [show] holds while the player answers, and returns the answer. */
  private suspend fun <Q, A> ask(question: Q, show: (Pending<Q, A>?) -> Unit): A {
    val pending = Pending<Q, A>(question)
    show(pending)
    try {
      return pending.answer.await()
    } finally {
      show(null)
    }
  }

  /** Back while [AppState.gameBusy]: it cancels a save choice, or asks before leaving. */
  fun back() {
    val conflict = saveConflict
    when {
      conflict != null -> conflict.answer.complete(null)
      playing -> dialog = GameDialog.Leave
    }
  }

  fun remove(game: LibraryGame) {
    if (game is LocalGame) removedLocalGame = true
    scope.launch {
      val removal =
        when (game) {
          is Rom -> state.games.remove(game)
          is LocalGame -> state.games.remove(game)
        }
      notes =
        when (removal) {
          is Removal.HoldsOtherFiles ->
            listOf("Nothing removed · The folder also holds ${removal.names.joinToString()}")
          is Removal.Failed -> listOf(labelled("Remove failed", removal.reason))
          Removal.Removed,
          Removal.NotOnDevice -> emptyList()
        }
    }
  }

  /** Asks to upload, once it knows what the sign-in may do, or says why it can't. */
  fun askToUpload() {
    notes = emptyList()
    checkingUploads = true
    // Undispatched, so an access that's known asks straight away.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      val access =
        try {
          state.romm.uploadAccess.filterNotNull().first()
        } finally {
          checkingUploads = false
        }
      // A game that started meanwhile comes first.
      if (playing) return@launch
      when (access) {
        UploadAccess.NotRequested -> dialog = GameDialog.AllowUploads(UPLOADS_NOT_REQUESTED)
        UploadAccess.AccountMayNot -> notes = listOf(UPLOADS_NOT_FOR_ACCOUNT)
        UploadAccess.Allowed,
        UploadAccess.Unknown -> dialog = GameDialog.Upload(access)
      }
    }
  }

  /** Uploads [game], with [access] saying what the sign-in may do. */
  fun upload(game: LocalGame, access: UploadAccess) {
    notes = emptyList()
    state.uploads.start(game, access)
  }

  /**
   * Shows in [notes] how the latest upload of the game [key] names ended. A refusal seen here as it
   * happens, which the sign-in's permissions may be the cause of, offers to sign in again. It runs
   * until cancelled.
   */
  suspend fun showUploads(key: GameKey) {
    var running = false
    state.uploads.upload
      .filterNotNull()
      .filter { it.game.key == key }
      .collect { upload ->
        val phase = upload.phase
        if (phase !is UploadPhase.Ended) {
          running = true
          return@collect
        }
        val outcome = phase.outcome
        notes = listOf(uploadNote(outcome))
        if (
          running &&
            outcome is UploadOutcome.Failed &&
            outcome.cause is RommException.Forbidden &&
            upload.access != UploadAccess.Allowed
        ) {
          dialog = GameDialog.AllowUploads(UPLOAD_FORBIDDEN)
        }
        running = false
      }
  }
}

private const val UPLOAD_FORBIDDEN = "Upload refused · No upload permission"

private fun uploadNote(outcome: UploadOutcome): String =
  when (outcome) {
    UploadResult.Added -> "Uploaded to RomM"
    UploadResult.AwaitingScan -> "Uploaded to RomM · In your library after its next scan"
    UploadOutcome.Stopped -> "Upload stopped"
    is UploadOutcome.Failed ->
      when (val cause = outcome.cause) {
        is RommException.Forbidden -> UPLOAD_FORBIDDEN
        is RommException.Status -> labelled("Upload refused", cause.message)
        else -> labelled("Upload failed", cause.message)
      }
    is UploadOutcome.ReadFailed -> labelled("File read failed", outcome.cause.message)
  }
