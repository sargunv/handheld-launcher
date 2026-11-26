package dev.sargunv.handheld.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.places.MenuButton

/** Why a sign-in that doesn't ask for permission to upload games can't upload them. */
internal const val UPLOADS_NOT_REQUESTED =
  "This device signed in without permission to upload games to your server."

/** Why an account that may not upload games can't, which signing in again doesn't change. */
internal const val UPLOADS_NOT_FOR_ACCOUNT =
  "Your RomM account may not upload games. An admin can allow it in RomM."

/**
 * Offers to sign in again, to the same server, asking for permission to upload games. [reason] says
 * why it's offered, such as RomM's refusal of an upload. Not now has focus first, so one press too
 * many signs nothing out.
 */
@Composable
internal fun AllowUploadsDialog(
  reason: String,
  onSignInAgain: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  Dialog(
    onDismissRequest,
    Modifier.widthIn(max = 640.dp).background(Placeholder.dialog).padding(24.dp),
    initialFocus = "allowUploads:cancel",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Sign in again to allow uploads?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(reason, color = Placeholder.text)
      Text(
        "You approve a new code in RomM on another device. Your games, saves and settings stay " +
          "on this device, and downloads carry on once you're signed in again.",
        color = Placeholder.mutedText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("allowUploads:cancel", "Not now", onConfirm = onDismissRequest)
        MenuButton("allowUploads:confirm", "Sign in again", onConfirm = onSignInAgain)
      }
    }
  }
}
