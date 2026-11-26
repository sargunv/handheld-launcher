package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.press
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FocusLazyTest {
  private fun ComposeUiTest.show(content: @Composable () -> Unit) {
    setContent { TestActionInputRoot { FocusRoot { FocusScope { content() } } } }
    waitForIdle()
  }

  @Composable
  private fun Item(key: String) {
    Box(Modifier.size(40.dp).testTag(key).focusItem(key))
  }

  /** A short row with an item to its right, where a step past the row's end could land. */
  @Composable
  private fun RowBesideItem(walls: Set<FocusDirection>) {
    Row {
      FocusLazyRow(Modifier.width(200.dp), walls = walls) {
        items(listOf("a", "b", "c")) { Item(it) }
      }
      Item("beside")
    }
  }

  @Test
  fun rowEndsAreWallsByDefault() = runComposeUiTest {
    show { RowBesideItem(HorizontalWalls) }
    press(List(3) { Key.DirectionRight })
    onNodeWithTag("c").assertIsFocused()
  }

  @Test
  fun rowEndsOpenWithoutWalls() = runComposeUiTest {
    show { RowBesideItem(emptySet()) }
    press(List(3) { Key.DirectionRight })
    onNodeWithTag("beside").assertIsFocused()
  }

  @Test
  fun gridSidesAreWallsAndFocusDoesntWrapLines() = runComposeUiTest {
    show {
      Row {
        FocusLazyVerticalGrid(GridCells.Fixed(3), Modifier.width(150.dp)) {
          items((1..9).map { "g$it" }) { Item(it) }
        }
        Item("beside")
      }
    }
    press(List(4) { Key.DirectionRight })
    onNodeWithTag("g3").assertIsFocused()
    press(Key.DirectionDown)
    onNodeWithTag("g6").assertIsFocused()
  }

  @Test
  fun focusMovesToTheFirstItemWhenTheFocusedOneGoesAway() = runComposeUiTest {
    val keys = mutableStateListOf("a", "b", "c")
    show { FocusLazyRow { items(keys, key = { it }) { Item(it) } } }
    press(List(2) { Key.DirectionRight })
    onNodeWithTag("c").assertIsFocused()
    keys.remove("c")
    waitForIdle()
    onNodeWithTag("a").assertIsFocused()
  }
}
