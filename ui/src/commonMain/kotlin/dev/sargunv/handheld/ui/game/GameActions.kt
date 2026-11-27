package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.downloads.Removal
import dev.sargunv.handheld.data.downloads.UploadResult
import dev.sargunv.handheld.data.downloads.uploadLocalGame
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_FOR_ACCOUNT
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_REQUESTED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.IOException

/** A save conflict waiting for the player's choice. */
internal class PendingChoice(val conflict: SaveConflict) {
  val choice = CompletableDeferred<SaveCopy?>()
}

/** An emulator that may still hold the game, waiting for the player's choice. */
internal class PendingStillRunning(val prompt: StillRunning) {
  val choice = CompletableDeferred<StillRunningChoice>()
}

/** How Upload to RomM is going. */
internal sealed interface UploadState {
  /** Asking what the sign-in may do. */
  data object Checking : UploadState

  /** Sending the file, [percent] of it once the server says how much it has. */
  data class Sending(val percent: Int?) : UploadState

  /** The server has the whole file, so the upload can't stop any more. */
  data object Finishing : UploadState
}

/** The dialog open over the Game screen. */
internal sealed interface GameDialog {
  data object Menu : GameDialog

  data object Remove : GameDialog

  /** Asks to upload, with what the sign-in may do. */
  data class Upload(val access: UploadAccess) : GameDialog

  data object StopUpload : GameDialog

  data object Leave : GameDialog

  /** Offers to sign in again with uploads allowed, saying [reason]. */
  data class AllowUploads(val reason: String) : GameDialog
}

/**
 * What the Game screen does with its game in [scope]: play, remove or upload it. A launch or an
 * upload ends the dialog that asks about it.
 */
@Stable
internal class GameActions(private val state: AppState, private val scope: CoroutineScope) {
  var dialog: GameDialog? by mutableStateOf(null)

  var playing by mutableStateOf(false)
    private set

  /** What the launch is doing before the game starts, such as getting its core. */
  var playProgress: String? by mutableStateOf(null)
    private set

  /** What the player should know about the last thing they did here, such as how a play went. */
  var notes by mutableStateOf(emptyList<String>())
    private set

  var saveConflict: PendingChoice? by mutableStateOf(null)
    private set

  var stillRunning: PendingStillRunning? by mutableStateOf(null)
    private set

  var upload: UploadState? by mutableStateOf(null)
    private set

  /** Whether a launch or an upload is under way, which leaving the screen would stop. */
  val busy: Boolean
    get() = playing || upload is UploadState.Sending || upload is UploadState.Finishing

  private var uploadJob: Job? = null

  private fun closeDialog(which: GameDialog) {
    if (dialog == which) dialog = null
  }

  fun play(game: LibraryGame, path: String) {
    playing = true
    notes = emptyList()
    scope.launch {
      try {
        notes =
          state.play(game.key) {
            state.launcher.play(
              game,
              path,
              state.settings.launch.toRules(),
              collectionsOf(state, game),
              onProgress = { playProgress = it },
              closeEmulators = state.settings.closeEmulators && state.launcher.canCloseEmulators,
              askStillRunning = { prompt ->
                val asking = PendingStillRunning(prompt)
                stillRunning = asking
                try {
                  asking.choice.await()
                } finally {
                  stillRunning = null
                }
              },
            ) { conflict ->
              val asking = PendingChoice(conflict)
              saveConflict = asking
              try {
                asking.choice.await()
              } finally {
                saveConflict = null
              }
            }
          }
      } finally {
        playing = false
        playProgress = null
        closeDialog(GameDialog.Leave)
      }
    }
  }

  /** Back while [busy]: it cancels a save choice, or asks before leaving or stopping the upload. */
  fun back() {
    val conflict = saveConflict
    when {
      conflict != null -> conflict.choice.complete(null)
      playing -> dialog = GameDialog.Leave
      upload is UploadState.Sending -> dialog = GameDialog.StopUpload
    }
  }

  fun remove(game: LibraryGame) {
    scope.launch {
      val removal =
        when (game) {
          is Rom -> state.games.remove(game)
          is LocalGame -> state.games.remove(game)
        }
      // A local game removed is gone, so its page has nothing left to show.
      if (game is LocalGame && removal == Removal.Removed) {
        state.navigator.back()
        return@launch
      }
      notes =
        when (removal) {
          is Removal.HoldsOtherFiles ->
            listOf("Nothing removed · The folder also holds ${removal.names.joinToString()}")
          is Removal.Failed -> listOf("Remove failed · ${removal.reason}")
          Removal.Removed,
          Removal.NotOnDevice -> emptyList()
        }
    }
  }

  /** Asks to upload, once it knows what the sign-in may do, or says why it can't. */
  fun askToUpload() {
    notes = emptyList()
    upload = UploadState.Checking
    scope.launch {
      val access =
        try {
          state.romm.uploadAccess()
        } finally {
          upload = null
        }
      // A game that started meanwhile comes first.
      if (playing) return@launch
      when (access) {
        null -> notes = listOf(SIGN_IN_TO_UPLOAD)
        UploadAccess.NotRequested -> dialog = GameDialog.AllowUploads(UPLOADS_NOT_REQUESTED)
        UploadAccess.AccountMayNot -> notes = listOf(UPLOADS_NOT_FOR_ACCOUNT)
        UploadAccess.Allowed,
        UploadAccess.Unknown -> dialog = GameDialog.Upload(access)
      }
    }
  }

  /** Uploads [game], with [access] saying what the sign-in may do. */
  fun upload(game: LocalGame, access: UploadAccess) {
    upload = UploadState.Sending(null)
    notes = emptyList()
    uploadJob = scope.launch {
      try {
        val client =
          state.romm.account?.client
            ?: run {
              notes = listOf(SIGN_IN_TO_UPLOAD)
              return@launch
            }
        val result =
          uploadLocalGame(
            client,
            game,
            onFinishing = {
              upload = UploadState.Finishing
              closeDialog(GameDialog.StopUpload)
            },
          ) { sent, total ->
            if (sent < total) upload = UploadState.Sending((sent * 100 / total).toInt())
          }
        notes =
          listOf(
            when (result) {
              UploadResult.Added -> "Uploaded to RomM"
              UploadResult.AwaitingScan -> "Uploaded to RomM · In your library after its next scan"
            },
          )
        // The library learns of the game, and this page becomes its page.
        state.romm.syncNow()
      } catch (e: RommException.Forbidden) {
        log.w(e) { "Upload of ${game.path} refused" }
        val refused = "Upload refused · No upload permission"
        notes = listOf(refused)
        // A sign-in whose permissions aren't known, or don't include uploading, may be why.
        if (access != UploadAccess.Allowed) dialog = GameDialog.AllowUploads(refused)
      } catch (e: RommException.Status) {
        notes = listOf("Upload refused · ${e.detail ?: e.message}")
      } catch (e: RommException) {
        notes = listOf("Upload failed · ${e.message}")
      } catch (e: IOException) {
        notes = listOf("File read failed · ${e.message}")
      } finally {
        upload = null
        uploadJob = null
        closeDialog(GameDialog.StopUpload)
      }
    }
  }

  /** Stops the upload, unless the server has the whole file meanwhile, and then it finishes. */
  fun stopUpload() {
    if (upload !is UploadState.Sending) return
    uploadJob?.cancel()
    notes = listOf("Upload stopped")
  }
}

private const val SIGN_IN_TO_UPLOAD = "Sign in to RomM to upload"

private val log = Logger.withTag("GameActions")
