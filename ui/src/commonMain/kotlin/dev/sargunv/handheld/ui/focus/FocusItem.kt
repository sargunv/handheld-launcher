package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.FocusRingVisibility
import com.composeunstyled.focusRing
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/**
 * How a focused item shows that it has focus. Every [focusItem] draws it, so focus looks the same
 * everywhere. The default is a neutral placeholder until the design system replaces it.
 */
@Immutable
data class FocusIndication(
  val width: Dp = 3.dp,
  val color: Color = Color(0xFFE8E4DC),
  val offset: Dp = 3.dp,
)

val LocalFocusIndication = staticCompositionLocalOf { FocusIndication() }

/**
 * Makes this element a focusable item: it takes focus, shows [LocalFocusIndication] while focused,
 * and handles the actions sent to it.
 *
 * [key] identifies the item within its [FocusScope], which puts focus back on it when the scope
 * becomes active again, and within its list (such as a [FocusLazyRow]), which puts focus back on it
 * when focus moves into the list. It has to be unique and stable within the scope.
 *
 * @param shape the item's outline, which the focus indication follows.
 * @param onConfirm called on [Action.Confirm] and on a tap.
 * @param onAction called with the other actions; return true to consume one.
 */
@Composable
fun Modifier.focusItem(
  key: String,
  shape: Shape = RectangleShape,
  onConfirm: (() -> Unit)? = null,
  onAction: (Action) -> Boolean = { false },
): Modifier {
  val scope = LocalFocusScope.current?.memory
  val group = LocalFocusMemory.current
  val requester = remember { FocusRequester() }
  val interactions = remember { MutableInteractionSource() }
  val indication = LocalFocusIndication.current
  val currentOnConfirm by rememberUpdatedState(onConfirm)
  val currentOnAction by rememberUpdatedState(onAction)

  DisposableEffect(scope, group, key, requester) {
    scope?.register(key, requester)
    group?.register(key, requester)
    onDispose {
      scope?.unregister(key, requester)
      group?.unregister(key, requester)
    }
  }

  return this.focusRing(
      interactionSource = interactions,
      width = indication.width,
      color = indication.color,
      shape = shape,
      offset = indication.offset,
      visibility = FocusRingVisibility.Focused,
    )
    .focusRequester(requester)
    .onFocusChanged {
      if (it.isFocused) {
        scope?.onItemFocused(key)
        group?.onItemFocused(key)
      }
    }
    .onAction { action ->
      val confirm = currentOnConfirm
      if (action == Action.Confirm && confirm != null) {
        confirm()
        true
      } else {
        currentOnAction(action)
      }
    }
    .pointerInput(requester) {
      detectTapGestures {
        requester.requestFocus()
        currentOnConfirm?.invoke()
      }
    }
    .focusable(interactionSource = interactions)
}
