package dev.sargunv.handheld.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.ui.assertFocused
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.press
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActionInputTest {
  /** The actions that reached the row, in order. */
  private val actions = mutableListOf<Action>()

  /** A row of items in a lazy list, which takes PageUp and PageDown for itself otherwise. */
  private fun ComposeUiTest.show(settings: InputSettings = InputSettings()) {
    setContent {
      TestActionInputRoot(settings) {
        FocusRoot {
          FocusScope {
            FocusLazyRow(
              Modifier.onAction {
                actions += it
                false
              },
            ) {
              items((1..10).map { "$it" }) { Box(Modifier.size(40.dp).focusItem(it)) }
            }
          }
        }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.hold(key: Key) {
    onRoot().performKeyInput {
      keyDown(key)
      advanceEventTime(1000)
      keyUp(key)
    }
    waitForIdle()
  }

  @Test
  fun heldDirectionsRepeatButHeldButtonsDont() = runComposeUiTest {
    show()
    hold(Key.DirectionRight)
    assertTrue(actions.count { it == Action.Right } > 1, "$actions")
    actions.clear()
    hold(Key.ButtonB)
    assertEquals(listOf(Action.Back), actions)
  }

  @Test
  fun bConfirmsAndAGoesBackWhenBConfirms() = runComposeUiTest {
    show(InputSettings(ConfirmButton.B))
    press(listOf(Key.ButtonB, Key.ButtonA))
    assertEquals(listOf(Action.Confirm, Action.Back), actions)
  }

  @Test
  fun keyboardKeysMeanTheirActionsAndNothingElse() = runComposeUiTest {
    show()
    press(listOf(Key.Enter, Key.Escape, Key.Tab, Key.PageDown, Key.PageUp))
    assertEquals(
      listOf(Action.Confirm, Action.Back, Action.Menu, Action.NextShelf, Action.PreviousShelf),
      actions,
    )
    // Tab would otherwise move focus, and the row would page.
    assertFocused("1")
  }
}
