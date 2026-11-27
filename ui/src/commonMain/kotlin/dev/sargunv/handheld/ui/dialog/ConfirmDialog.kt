package dev.sargunv.handheld.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.places.MenuButton

/**
 * Asks before an action: [title], an optional [fact], then Cancel and [confirmLabel] in a row.
 * Cancel has the focus first, so one press too many does nothing. The buttons' keys are
 * `$key:cancel` and `$key:confirm`.
 */
@Composable
internal fun ConfirmDialog(
  key: String,
  title: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismissRequest: () -> Unit,
  fact: String? = null,
  cancelLabel: String = "Cancel",
) {
  Dialog(onDismissRequest, Modifier.widthIn(max = 640.dp), initialFocus = "$key:cancel") {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      fact?.let { Text(it, color = Placeholder.mutedText) }
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("$key:cancel", cancelLabel, onConfirm = onDismissRequest)
        MenuButton("$key:confirm", confirmLabel, onConfirm = onConfirm)
      }
    }
  }
}
