package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import com.mikepenz.aboutlibraries.entity.Library
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.ui.focus.LocalFocusScope

/** What the lower screen describes: the item focused on the upper one. */
@Immutable
sealed interface Focused {
  data class OnGame(val game: LibraryGame) : Focused

  data class OnCollection(val collection: Collection) : Focused

  data class OnComponent(val library: Library) : Focused
}

/**
 * Has the lower screen describe [focused] while this item has focus, and while a dialog or screen
 * over it holds focus instead. It stops when focus moves to another item, or this one goes away.
 */
fun Modifier.describes(state: AppState, focused: Focused): Modifier =
  this then DescribesElement(state, focused)

private data class DescribesElement(val state: AppState, val focused: Focused) :
  ModifierNodeElement<DescribesNode>() {
  override fun create() = DescribesNode(state, focused)

  override fun update(node: DescribesNode) = node.update(state, focused)
}

private class DescribesNode(private var state: AppState, private var focused: Focused) :
  Modifier.Node(), FocusEventModifierNode, CompositionLocalConsumerModifierNode {
  /** What this item has the lower screen describe, if anything. */
  private var described: Focused? = null

  fun update(state: AppState, focused: Focused) {
    val describing = described != null
    stop()
    this.state = state
    this.focused = focused
    if (describing) describe()
  }

  override fun onFocusEvent(focusState: FocusState) {
    when {
      focusState.isFocused -> describe()
      isAttached && currentValueOf(LocalFocusScope)?.isActive == false -> Unit
      else -> stop()
    }
  }

  override fun onDetach() = stop()

  private fun describe() {
    state.focused = focused
    described = focused
  }

  private fun stop() {
    if (described != null && state.focused === described) state.focused = null
    described = null
  }
}
