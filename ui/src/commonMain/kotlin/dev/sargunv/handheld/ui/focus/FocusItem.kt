package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.composeunstyled.FocusRingVisibility
import com.composeunstyled.focusRing
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.onAction

/**
 * Makes this element a focusable item: it takes focus, shows a focus ring while focused, and
 * handles the actions sent to it.
 *
 * [key] identifies the item within its [FocusScope], which puts focus back on it when the scope
 * becomes active again, and within its list (such as a [FocusLazyRow]), which puts focus back on it
 * when focus moves into the list. It has to be unique and stable within the scope. It's also the
 * item's test tag, unless a test tag before this modifier sets another.
 *
 * Accessibility services see the item as one element, with the text inside it as its label, and the
 * screen reader's focus follows the controller's. A screen reader's activation confirms it.
 *
 * @param shape the item's outline, which the focus ring follows.
 * @param onConfirm called on [Action.Confirm] and on a tap.
 * @param role what kind of element accessibility services say it is. Items that confirm are buttons
 *   by default.
 * @param description the item's label for accessibility services, in place of the text inside it,
 *   for an item whose text doesn't say what it is, such as a cover.
 * @param stateDescription the item's state, for accessibility services, when its text doesn't say.
 * @param onAction called with the other actions; return true to consume one.
 */
@Composable
fun Modifier.focusItem(
  key: String,
  shape: Shape = RectangleShape,
  onConfirm: (() -> Unit)? = null,
  role: Role? = if (onConfirm != null) Role.Button else null,
  description: String? = null,
  stateDescription: String? = null,
  onAction: (Action) -> Boolean = { false },
): Modifier {
  val focusScope = LocalFocusScope.current
  val scope = focusScope?.memory
  val list = LocalFocusMemory.current
  val requester = remember { FocusRequester() }
  val interactions = remember { MutableInteractionSource() }
  val currentOnConfirm by rememberUpdatedState(onConfirm)
  val currentOnAction by rememberUpdatedState(onAction)

  DisposableEffect(scope, list, key, requester) {
    scope?.register(key, requester)
    list?.register(key, requester)
    onDispose {
      scope?.unregister(key, requester)
      list?.unregister(key, requester)
    }
  }

  return this.testTag(key)
    .semantics(mergeDescendants = true) {
      role?.let { this.role = it }
      stateDescription?.let { this.stateDescription = it }
      // Only in the active scope: a screen reader can reach items behind a dialog.
      if (onConfirm != null && focusScope?.isActive != false) {
        onClick {
          requester.requestFocus()
          currentOnConfirm?.invoke()
          true
        }
      }
    }
    .focusRing(
      interactionSource = interactions,
      width = 3.dp,
      color = Placeholder.text,
      shape = shape,
      offset = 3.dp,
      visibility = FocusRingVisibility.Focused,
    )
    .focusRequester(requester)
    .onFocusChanged {
      if (it.isFocused) {
        scope?.onItemFocused(key, list)
        list?.onItemFocused(key)
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
    // Last, since it clears what the modifiers after it would add, but not what came before.
    .then(
      if (description != null) Modifier.clearAndSetSemantics { contentDescription = description }
      else Modifier,
    )
}
