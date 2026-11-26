package dev.sargunv.handheld.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/**
 * A dialog over the screen. Place it last in a box that fills the screen, so it covers what came
 * before it.
 *
 * While open it holds focus: directions can't leave it, the content behind it can't be tapped or
 * act on the actions the dialog doesn't handle, and [Action.Back], the system back gesture or a tap
 * outside the panel calls [onDismissRequest]. When it closes, the screen's [FocusScope] puts focus
 * back where it was.
 *
 * @param initialFocus the key of the item to focus first; the first item by default.
 * @param modifier applies to the panel.
 */
@Composable
fun Dialog(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  initialFocus: String? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val dismiss by rememberUpdatedState(onDismissRequest)
  if (LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { dismiss() },
    )
  }
  Box(
    Modifier.fillMaxSize().background(ScrimColor).pointerInput(Unit) {
      detectTapGestures { dismiss() }
    },
    contentAlignment = Alignment.Center,
  ) {
    FocusScope(
      modifier =
        modifier
          // A screen reader's dismiss gesture closes it, like a tap outside.
          .semantics {
            this.dismiss {
              dismiss()
              true
            }
          }
          .pointerInput(Unit) { detectTapGestures {} }
          .focusProperties { onExit = { cancelFocusChange() } }
          .onAction { action ->
            if (action == Action.Back) dismiss()
            // The screen behind doesn't act while the dialog is open. Directions go on, to move
            // focus within the dialog.
            action.direction == null
          },
      initialFocus = initialFocus,
      isModal = true,
      content = content,
    )
  }
}

private val ScrimColor = Color.Black.copy(alpha = 0.6f)
