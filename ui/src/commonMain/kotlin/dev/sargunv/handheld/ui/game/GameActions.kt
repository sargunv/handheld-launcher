package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** The dialog open over the Game screen. */
internal sealed interface GameDialog {
  data object Menu : GameDialog

  data object Remove : GameDialog

  data object Upload : GameDialog

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

  /** How an upload under way is going, in a few words, such as "Uploading 42%". */
  var uploadProgress: String? by mutableStateOf(null)
    private set

  /** Whether the server has the whole file, so the upload can't stop any more. */
  var uploadFinishing by mutableStateOf(false)
    private set

  /** Whether Upload to RomM is asking what the sign-in may do. */
  var checkingUpload by mutableStateOf(false)
    private set

  val uploading: Boolean
    get() = uploadProgress != null

  /** Whether a launch or an upload is under way, which leaving the screen would stop. */
  val busy: Boolean
    get() = playing || uploading

  private var upload: Job? = null

  // What the sign-in may do about uploads, as of the last Upload to RomM.
  private var uploadAccess: UploadAccess? = null

  private fun closeDialog(which: GameDialog) {
    if (dialog == which) dialog = null
  }

  fun play(game: LibraryGame, path: String) {
    playing = true
    notes = emptyList()
    scope.launch {
      try {
        val collections = collectionsOf(state, game)
        val rules = state.settings.launch.toRules()
        notes =
          state.launcher.play(
            game,
            path,
            rules,
            collections,
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
      uploading && !uploadFinishing -> dialog = GameDialog.StopUpload
    }
  }

  fun remove(game: LibraryGame) {
    scope.launch {
      val removal =
        when (game) {
          is Rom -> state.downloads.remove(game)
          is LocalGame -> state.downloads.remove(game)
        }
      // A local game removed is gone, so its page has nothing left to show.
      if (game is LocalGame && removal == Removal.Removed) {
        state.navigator.back()
        return@launch
      }
      notes =
        when (removal) {
          is Removal.HoldsOtherFiles ->
            listOf(
              "The game's folder also holds ${removal.names.joinToString()}, " +
                "so nothing was removed.",
            )
          is Removal.Failed -> listOf("Couldn't remove the game: ${removal.reason}")
          Removal.Removed,
          Removal.NotOnDevice -> emptyList()
        }
    }
  }

  /** Asks to upload, once it knows what the sign-in may do, or says why it can't. */
  fun askToUpload() {
    notes = emptyList()
    checkingUpload = true
    scope.launch {
      val access =
        try {
          state.romm.uploadAccess()
        } finally {
          checkingUpload = false
        }
      uploadAccess = access
      // A game that started meanwhile comes first.
      if (playing) return@launch
      when (access) {
        null -> notes = listOf(SIGN_IN_TO_UPLOAD)
        UploadAccess.NotRequested -> dialog = GameDialog.AllowUploads(UPLOADS_NOT_REQUESTED)
        UploadAccess.AccountMayNot -> notes = listOf(UPLOADS_NOT_FOR_ACCOUNT)
        UploadAccess.Allowed,
        UploadAccess.Unknown -> dialog = GameDialog.Upload
      }
    }
  }

  fun upload(game: LocalGame) {
    uploadProgress = "Uploading"
    notes = emptyList()
    upload = scope.launch {
      try {
        val client =
          state.romm.client()
            ?: run {
              notes = listOf(SIGN_IN_TO_UPLOAD)
              return@launch
            }
        val result = client.use {
          uploadLocalGame(
            it,
            game,
            onFinishing = {
              uploadFinishing = true
              uploadProgress = "Adding to your library"
              closeDialog(GameDialog.StopUpload)
            },
          ) { sent, total ->
            if (sent < total) uploadProgress = "Uploading ${sent * 100 / total}%"
          }
        }
        notes =
          listOf(
            when (result) {
              UploadResult.Added -> "Uploaded to RomM."
              UploadResult.AwaitingScan ->
                "Uploaded to RomM. It joins your library when RomM next scans it."
            },
          )
        // The library learns of the game, and this page becomes its page.
        state.syncNow()
      } catch (e: RommException.Forbidden) {
        val refused = "RomM didn't allow the upload${e.detail?.let { ": $it" } ?: ""}."
        notes = listOf("$refused Your account needs permission to upload games.")
        // A sign-in whose permissions aren't known, or don't include uploading, may be why.
        if (uploadAccess != UploadAccess.Allowed) {
          dialog =
            GameDialog.AllowUploads(
              "$refused This device's sign-in may not have permission to upload games.",
            )
        }
      } catch (e: RommException.Status) {
        notes = listOf("RomM refused the upload: ${e.detail ?: e.message}")
      } catch (e: RommException) {
        notes = listOf("Couldn't upload the game: ${e.message}")
      } catch (e: IOException) {
        notes = listOf("Couldn't read the game's file: ${e.message}")
      } finally {
        uploadProgress = null
        upload = null
        uploadFinishing = false
        closeDialog(GameDialog.StopUpload)
      }
    }
  }

  /** Stops the upload, unless the server has the whole file meanwhile, and then it finishes. */
  fun stopUpload() {
    if (uploadFinishing) return
    upload?.cancel()
    notes = listOf("The upload stopped.")
  }
}

private const val SIGN_IN_TO_UPLOAD = "Sign in to RomM to upload the game."
