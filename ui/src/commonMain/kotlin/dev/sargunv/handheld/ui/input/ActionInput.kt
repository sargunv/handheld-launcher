package dev.sargunv.handheld.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.node.currentValueOf
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
class ActionDecoder internal constructor(settings: InputSettings, heldKeys: HeldKeys) {
  var settings: InputSettings = settings
    internal set

  internal var heldKeys: HeldKeys = heldKeys

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
  val decoder = remember { ActionDecoder(settings, heldKeys) }
  decoder.settings = settings
  decoder.heldKeys = heldKeys
  val focusManager = LocalFocusManager.current
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(heldKeys, windowFocused) { if (!windowFocused) heldKeys.releaseAll() }

  CompositionLocalProvider(LocalActionDecoder provides decoder) {
    Box(
      modifier.onKeyEvent { event ->
        if (decoder.settings.actionFor(event.key) == null) return@onKeyEvent false
        // At an edge this does nothing, and focus stays where it was.
        decoder.decode(event)?.direction?.let { focusManager.moveFocus(it.focusDirection) }
        // A mapped key means its action and nothing else, even when nothing handled the action.
        // Left unconsumed, the platform would act on it too: Tab traverses focus, and Android
        // turns unhandled gamepad buttons into fallback keys such as Back.
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
 * directions move focus.
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
  Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
  override fun onKeyEvent(event: KeyEvent): Boolean {
    val decoder = currentValueOf(LocalActionDecoder) ?: return false
    val action = decoder.decode(event) ?: return false
    return onAction(action)
  }

  override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}
