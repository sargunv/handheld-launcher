package dev.sargunv.handheld.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.navigation.BackHandler
import kotlinx.coroutines.flow.first

/**
 * Closes every [Dialog] open below where it's provided as [LocalDialogs] at once, as the system's
 * HOME does.
 */
@Stable
class Dialogs {
  internal var closes by mutableIntStateOf(0)
    private set

  /** Asks each open dialog to close, as if the player had dismissed it. */
  fun closeAll() {
    closes++
  }
}

/** The [Dialogs] that can close the dialogs below, or null where nothing closes them at once. */
val LocalDialogs = staticCompositionLocalOf<Dialogs?> { null }

/**
 * A dialog over the screen, on a panel. Place it last in a box that fills the screen, so it covers
 * what came before it.
 *
 * While open it holds focus: directions can't leave it, the content behind it can't be tapped or
 * act on the actions the dialog doesn't handle, and [Action.Back], the system back gesture or a tap
 * outside the panel calls [onDismissRequest]. When it closes, the screen's [FocusScope] puts focus
 * back where it was. [Dialogs.closeAll] closes it too.
 *
 * @param initialFocus the key of the item to focus first; the first item by default.
 * @param modifier applies to the panel, such as its size.
 * @param alignment where the panel sits on the screen: the middle by default, or an edge for a side
 *   menu.
 */
@Composable
fun Dialog(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  initialFocus: String? = null,
  alignment: Alignment = Alignment.Center,
  content: @Composable BoxScope.() -> Unit,
) {
  val dismiss by rememberUpdatedState(onDismissRequest)
  val dialogs = LocalDialogs.current
  if (dialogs != null) {
    // Only a request made while it's open closes it.
    val closesAtOpen = remember(dialogs) { dialogs.closes }
    LaunchedEffect(dialogs) {
      snapshotFlow { dialogs.closes }.first { it != closesAtOpen }
      dismiss()
    }
  }
  Box(
    Modifier.fillMaxSize().background(ScrimColor).pointerInput(Unit) {
      detectTapGestures { dismiss() }
    },
    contentAlignment = alignment,
  ) {
    BackHandler(onBack = { dismiss() }) {
      FocusScope(
        modifier =
          modifier
            .background(Placeholder.dialog)
            .padding(24.dp)
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
              // The screen behind doesn't act while the dialog is open. Back goes on to close it,
              // and directions to move focus within it.
              action != Action.Back && action.direction == null
            },
        initialFocus = initialFocus,
        isModal = true,
        content = content,
      )
    }
  }
}

private val ScrimColor = Color.Black.copy(alpha = 0.6f)
