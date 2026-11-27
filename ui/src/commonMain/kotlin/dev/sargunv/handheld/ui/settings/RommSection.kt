package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.ui.dialog.ConfirmDialog
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.signin.AllowUploadsDialog
import dev.sargunv.handheld.ui.signin.Help
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_FOR_ACCOUNT
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_REQUESTED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The RomM section's state. It asks what this sign-in may upload only while [shown]. */
@Composable
internal fun rememberRommSection(state: AppState, shown: Boolean): RommSection {
  val scope = rememberCoroutineScope()
  val section =
    rememberSaveable(state, saver = RommSection.saver(state, scope)) { RommSection(state, scope) }
  val signedIn = state.account.signedIn == true
  // Asked again after each sync, since the account's permissions can change.
  LaunchedEffect(section, state.sync, signedIn, shown) {
    if (signedIn && shown) section.askUploadAccess()
  }
  return section
}

internal class RommSection(val state: AppState, private val scope: CoroutineScope) {
  /** What this sign-in may upload, or null while it's being asked. */
  var uploadAccess: UploadAccess? by mutableStateOf(null)
    private set

  var confirmSignOut by mutableStateOf(false)

  var confirmAllowUploads by mutableStateOf(false)

  suspend fun askUploadAccess() {
    uploadAccess = state.romm.uploadAccess()
  }

  fun signInAgain() {
    scope.launch { state.signOut(keepQueue = true) }
  }

  @Composable
  fun Dialogs() {
    if (confirmSignOut) {
      SignOutDialog(
        onSignOut = {
          confirmSignOut = false
          scope.launch { state.signOut() }
        },
        onDismissRequest = { confirmSignOut = false },
      )
    }
    if (confirmAllowUploads) {
      AllowUploadsDialog(
        UPLOADS_NOT_REQUESTED,
        onSignInAgain = {
          confirmAllowUploads = false
          scope.launch { state.signOut(allowUploads = true, keepQueue = true) }
        },
        onDismissRequest = { confirmAllowUploads = false },
      )
    }
  }

  companion object {
    fun saver(state: AppState, scope: CoroutineScope) =
      Saver<RommSection, List<Boolean>>(
        save = { listOf(it.confirmSignOut, it.confirmAllowUploads) },
        restore = { (signOut, allowUploads) ->
          RommSection(state, scope).apply {
            confirmSignOut = signOut
            confirmAllowUploads = allowUploads
          }
        },
      )
  }
}

internal fun LazyListScope.romm(section: RommSection) {
  sectionHeading("RomM")
  val state = section.state
  val uploadAccess = section.uploadAccess
  val view = state.settingsView
  if (state.account.signedIn != true) {
    item(key = "signedOut") {
      SettingRow(
        view,
        "settings:romm:server",
        "Server",
        "Not signed in",
        Help("Not signed in", "Sign in on the main screen to reach your library."),
      )
    }
    return
  }
  item(key = "server") {
    val server by produceState<String?>(null) { value = state.credentials.load()?.baseUrl }
    SettingRow(
      view,
      "settings:romm:server",
      "Server",
      server ?: "",
      Help("Server", "The RomM server this device's library comes from."),
    )
  }
  item(key = "account") {
    // Asked of the server each time, since nothing keeps it.
    val account by
      produceState<String?>(null, state.sync) {
        value =
          try {
            state.romm.client()?.use { it.me().username } ?: "Unknown"
          } catch (_: RommException) {
            "Unreachable"
          }
      }
    SettingRow(
      view,
      "settings:romm:account",
      "Account",
      account ?: "",
      Help("Account", "The RomM user this device signed in as."),
    )
  }
  item(key = "library") {
    SettingRow(
      view,
      "settings:romm:sync",
      "Library",
      state.sync.label,
      Help("Library", "How the latest sync of the library with the server went."),
    )
  }
  if (state.sync == SyncStatus.Unauthorized) {
    item(key = "signInAgain") {
      SettingRow(
        view,
        "settings:romm:signInAgain",
        "Sign in again",
        null,
        Help("Sign in again", "Your games and saves stay on this device."),
        onConfirm = section::signInAgain,
      )
    }
  }
  item(key = "uploads") {
    SettingRow(
      view,
      "settings:romm:uploads",
      "Uploads",
      uploadsSummary(uploadAccess),
      Help("Uploads", uploadsHelp(uploadAccess)),
    )
  }
  // A refused sign-in offers to sign in again already.
  if (uploadAccess == UploadAccess.NotRequested && state.sync != SyncStatus.Unauthorized) {
    item(key = "allowUploads") {
      SettingRow(
        view,
        "settings:romm:allowUploads",
        "Allow uploads",
        null,
        Help("Allow uploads", "Signs in again, asking for permission to upload games."),
        onConfirm = { section.confirmAllowUploads = true },
      )
    }
  }
  item(key = "syncNow") {
    val syncing = state.sync == SyncStatus.Syncing || state.sync == SyncStatus.FirstSync
    SettingRow(
      view,
      "settings:romm:syncNow",
      "Sync now",
      null,
      Help("Sync now", "Syncs the library with the server now, rather than at the next start."),
      enabled = !syncing,
      onConfirm = { if (!syncing) state.syncNow() },
    )
  }
  item(key = "signOut") {
    SettingRow(
      view,
      "settings:romm:signOut",
      "Sign out",
      null,
      Help("Sign out", "Your downloaded games and saves stay on this device."),
      onConfirm = { section.confirmSignOut = true },
    )
  }
}

/** What the RomM section says about uploading games, or nothing while it's being asked. */
internal fun uploadsSummary(access: UploadAccess?): String =
  when (access) {
    null -> ""
    UploadAccess.Allowed -> "Allowed"
    UploadAccess.NotRequested -> "Not allowed by this sign-in"
    UploadAccess.AccountMayNot -> "Not allowed for your account"
    UploadAccess.Unknown -> "Up to your API token"
  }

private fun uploadsHelp(access: UploadAccess?): String =
  when (access) {
    null -> "Whether this device may upload games that aren't on your server."
    UploadAccess.Allowed ->
      "This device may upload games that aren't on your server, from the game's page."
    UploadAccess.NotRequested -> UPLOADS_NOT_REQUESTED
    UploadAccess.AccountMayNot -> UPLOADS_NOT_FOR_ACCOUNT
    UploadAccess.Unknown -> "RomM doesn't say whether an API token may upload games."
  }

/** Asks before signing out. */
@Composable
internal fun SignOutDialog(onSignOut: () -> Unit, onDismissRequest: () -> Unit) {
  ConfirmDialog(
    "signOut",
    "Sign out of RomM?",
    "Sign out",
    onConfirm = onSignOut,
    onDismissRequest = onDismissRequest,
    fact = "Your downloaded games and saves stay on this device.",
  )
}
