package dev.sargunv.handheld.ui.signin

import androidx.compose.runtime.Composable
import dev.sargunv.handheld.ui.dialog.ConfirmDialog

/** Why a sign-in that doesn't ask for permission to upload games can't upload them. */
internal const val UPLOADS_NOT_REQUESTED = "This sign-in doesn't allow uploads."

/** Why an account that may not upload games can't, which signing in again doesn't change. */
internal const val UPLOADS_NOT_FOR_ACCOUNT = "Your RomM account can't upload games."

/**
 * Offers to sign in again, to the same server, asking for permission to upload games. [reason] says
 * why it's offered, such as RomM's refusal of an upload.
 */
@Composable
internal fun AllowUploadsDialog(
  reason: String,
  onSignInAgain: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  ConfirmDialog(
    "allowUploads",
    "Sign in again to allow uploads?",
    "Sign in again",
    onConfirm = onSignInAgain,
    onDismissRequest = onDismissRequest,
    fact = reason,
    cancelLabel = "Not now",
  )
}
