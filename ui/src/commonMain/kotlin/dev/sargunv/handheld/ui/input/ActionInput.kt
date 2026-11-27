package dev.sargunv.handheld.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The keys held down in a window, which tells a repeat from a fresh press.
 *
 * The platform passes every key event the window receives to [track] before Compose dispatches it,
 * whatever has focus. A key that goes up while nothing in Compose has focus still counts as
 * released, so its next press isn't taken for a repeat.
 */
class HeldKeys {
  private val held = mutableSetOf<Key>()

  /** Whether the latest key down was of a key that was already down. */
  internal var lastDownRepeats = false
    private set

  /** Records a key going down or up. */
  fun track(event: KeyEvent) {
    when (event.type) {
      KeyEventType.KeyDown -> lastDownRepeats = !held.add(event.key)
      KeyEventType.KeyUp -> held.remove(event.key)
    }
  }

  /** Forgets every held key, as when the window loses focus and won't see them go up. */
  fun releaseAll() = held.clear()
}

/** Turns key events into [Action]s. One lives at the root of each window's content. */
internal class ActionDecoder(private val settings: InputSettings, private val heldKeys: HeldKeys) {
  /**
   * The action [event] performs, or null if it performs none: key ups, unmapped keys, and repeats
   * of actions that don't repeat.
   */
  fun decode(event: KeyEvent): Action? {
    if (event.type != KeyEventType.KeyDown) return null
    val action = settings.actionFor(event.key) ?: return null
    return if (heldKeys.lastDownRepeats && !action.repeats) null else action
  }
}

internal val LocalActionDecoder = staticCompositionLocalOf<ActionDecoder?> { null }

/** The input settings of the nearest [ActionInputRoot]. */
val LocalInputSettings = compositionLocalOf { InputSettings() }

/**
 * The root of a window's controller-driven content. It decodes key events into [Action]s for every
 * [onAction] below it, and moves focus for the directions nobody else handled. It consumes every
 * key that maps to an action, so an action nobody handles does nothing.
 *
 * @param heldKeys the window's held keys, which the platform keeps up to date.
 */
@Composable
fun ActionInputRoot(
  settings: InputSettings,
  heldKeys: HeldKeys,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val decoder = remember(settings, heldKeys) { ActionDecoder(settings, heldKeys) }
  val focusManager = LocalFocusManager.current
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(heldKeys, windowFocused) { if (!windowFocused) heldKeys.releaseAll() }

  CompositionLocalProvider(
    LocalActionDecoder provides decoder,
    LocalInputSettings provides settings,
  ) {
    Box(
      modifier.onKeyEvent { event ->
        // A mapped key means its action and nothing else, even on its key up or a repeat, and even
        // when nothing handled the action. Left unconsumed, the platform would act on it too: Tab
        // traverses focus, and Android turns unhandled gamepad buttons into fallback keys such as
        // Back.
        val action =
          decoder.decode(event) ?: return@onKeyEvent settings.actionFor(event.key) != null
        // At an edge this does nothing, and focus stays where it was.
        action.direction?.let { focusManager.moveFocus(it.focusDirection) }
        true
      },
    ) {
      content()
    }
  }
}

internal val Direction.focusDirection: FocusDirection
  get() =
    when (this) {
      Direction.Up -> FocusDirection.Up
      Direction.Down -> FocusDirection.Down
      Direction.Left -> FocusDirection.Left
      Direction.Right -> FocusDirection.Right
    }

/**
 * Calls [onAction] for each [Action] that reaches this element while it or a descendant has focus.
 * Return true to consume the action; unconsumed actions continue to the ancestors, and unconsumed
 * directions move focus. Other actions go straight to the next [onAction] above, so no other key
 * handler in between acts on their keys.
 */
fun Modifier.onAction(onAction: (Action) -> Boolean): Modifier = this then OnActionElement(onAction)

private data class OnActionElement(val onAction: (Action) -> Boolean) :
  ModifierNodeElement<OnActionNode>() {
  override fun create() = OnActionNode(onAction)

  override fun update(node: OnActionNode) {
    node.onAction = onAction
  }
}

private class OnActionNode(var onAction: (Action) -> Boolean) :
  Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode, TraversableNode {
  override val traverseKey = OnActionTraverseKey

  override fun onKeyEvent(event: KeyEvent): Boolean {
    val decoder = currentValueOf(LocalActionDecoder) ?: return false
    val action = decoder.decode(event) ?: return false
    if (onAction(action)) return true
    // Directions go on as keys, so the root moves focus once nobody else wants them.
    if (action.direction != null) return false
    // Hand the action to the handlers above directly, rather than letting the key go on. Other key
    // handlers on the way, such as a lazy list's paging on PageUp and PageDown, would act on a key
    // that means an action here.
    traverseAncestors(OnActionTraverseKey) { node -> !(node as OnActionNode).onAction(action) }
    return true
  }

  override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

private object OnActionTraverseKey
