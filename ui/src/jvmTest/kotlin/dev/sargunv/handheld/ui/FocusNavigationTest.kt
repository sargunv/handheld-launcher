package dev.sargunv.handheld.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.ui.demo.DemoLibrary
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FocusNavigationTest {
  private val shelfA = DemoLibrary.shelves[0]
  private val shelfB = DemoLibrary.shelves[1]
  private val shelfC = DemoLibrary.shelves[2]

  private fun tile(shelf: Int, index: Int) =
    "game:${DemoLibrary.shelves[shelf].games[index].rommId}"

  /** Presses each key in turn, letting the UI settle after each. */
  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      onRoot().performKeyInput { pressKey(key) }
      waitForIdle()
    }
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  private fun ComposeUiTest.assertFocused(tag: String) {
    onNodeWithTag(tag).assertIsFocused()
  }

  private fun ComposeUiTest.launch(layout: DualScreenLayout = DualScreenLayout.Separate) {
    setContent { App(layout) }
    waitForIdle()
  }

  @Test
  fun firstTileHasFocusAtLaunch() = runComposeUiTest {
    launch()
    assertFocused(tile(0, 0))
  }

  @Test
  fun dpadMovesAlongAndAcrossShelves() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight))
    assertFocused(tile(0, 3))
    press(Key.DirectionDown)
    assertFocused(tile(1, 3))
    press(Key.DirectionDown)
    assertFocused(tile(2, 3))
  }

  @Test
  fun shelfEndsAreWalls() = runComposeUiTest {
    launch()
    press(Key.DirectionLeft)
    assertFocused(tile(0, 0))
    press(Key.DirectionDown)
    repeat(shelfB.games.size + 2) { press(Key.DirectionRight) }
    assertFocused(tile(1, shelfB.games.lastIndex))
  }

  @Test
  fun returningToAShelfRestoresItsFocusedTile() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionDown))
    assertFocused(tile(1, 3))
    press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft))
    assertFocused(tile(1, 0))
    press(Key.DirectionUp)
    assertFocused(tile(0, 3))
    press(Key.DirectionDown)
    assertFocused(tile(1, 0))
  }

  @Test
  fun longShelfScrollsToKeepFocusInView() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionDown, Key.DirectionDown))
    repeat(shelfC.games.lastIndex) { press(Key.DirectionRight) }
    assertFocused(tile(2, shelfC.games.lastIndex))
    onNodeWithTag(tile(2, shelfC.games.lastIndex)).assertIsDisplayed()
  }

  @Test
  fun shoulderButtonsJumpBetweenShelves() = runComposeUiTest {
    launch()
    press(listOf(Key.ButtonR1, Key.ButtonR1))
    assertFocused(tile(2, 0))
    press(Key.ButtonL1)
    assertFocused(tile(1, 0))
  }

  @Test
  fun backRestoresFocusToTheTileThatOpenedTheScreen() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight))
    assertFocused(tile(2, 2))
    press(Key.ButtonA)
    assertFocused("play")
    press(Key.DirectionRight)
    assertFocused("options")
    press(Key.ButtonB)
    assertFocused(tile(2, 2))
  }

  @Test
  fun heldDirectionsRepeatButHeldButtonsDont() = runComposeUiTest {
    launch()
    onRoot().performKeyInput {
      keyDown(Key.DirectionRight)
      advanceEventTime(1000)
      keyUp(Key.DirectionRight)
    }
    waitForIdle()
    val focusedTile =
      shelfA.games.indices.single { index ->
        onAllNodes(hasTestTag(tile(0, index)) and isFocused()).fetchSemanticsNodes().isNotEmpty()
      }
    assertTrue(focusedTile > 1, "held right moved $focusedTile tiles")
    press(listOf(Key.ButtonA, Key.DirectionRight, Key.ButtonA))
    assertFocused("menu:first")
    // A repeated Back would close the dialog and then leave the detail screen.
    onRoot().performKeyInput {
      keyDown(Key.ButtonB)
      advanceEventTime(1000)
      keyUp(Key.ButtonB)
    }
    waitForIdle()
    assertFocused("options")
  }

  @Test
  fun backOnTheFirstScreenKeepsFocus() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionRight, Key.ButtonB))
    assertFocused(tile(0, 1))
  }

  @Test
  fun dialogTrapsFocusAndRestoresItOnClose() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionDown, Key.DirectionRight))
    press(Key.ButtonX)
    assertFocused("menu:first")
    press(listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight))
    assertFocused("menu:first")
    repeat(5) { press(Key.DirectionDown) }
    assertFocused("menu:close")
    press(Key.ButtonB)
    assertFocused(tile(1, 1))
  }

  @Test
  fun confirmingADialogItemRestoresFocus() = runComposeUiTest {
    launch()
    press(listOf(Key.ButtonA, Key.DirectionRight, Key.ButtonA))
    assertFocused("menu:first")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    assertFocused("options")
  }

  @Test
  fun confirmOnEastSwapsConfirmAndBack() = runComposeUiTest {
    launch()
    press(Key.ButtonA)
    press(listOf(Key.DirectionRight, Key.DirectionRight))
    assertFocused("confirm")
    // Confirm is south, so A confirms and swaps the setting to east.
    press(Key.ButtonA)
    // Now B confirms, and swaps it back to south.
    press(Key.ButtonB)
    assertFocused("confirm")
    // So B goes back again.
    press(Key.ButtonB)
    assertFocused(tile(0, 0))
  }

  @Test
  fun eastConfirmOpensWithBAndGoesBackWithA() = runComposeUiTest {
    launch()
    press(listOf(Key.ButtonA, Key.DirectionRight, Key.DirectionRight, Key.ButtonA))
    press(Key.ButtonA)
    assertFocused(tile(0, 0))
    press(listOf(Key.DirectionRight, Key.ButtonB))
    assertFocused("play")
    press(Key.ButtonA)
    assertFocused(tile(0, 1))
  }

  @Test
  fun keyboardDrivesTheSameActions() = runComposeUiTest {
    launch()
    press(listOf(Key.DirectionDown, Key.Enter))
    assertFocused("play")
    press(Key.Escape)
    assertFocused(tile(1, 0))
    press(Key.Tab)
    assertFocused("menu:first")
    press(Key.Escape)
    assertFocused(tile(1, 0))
  }

  @Test
  fun focusWorksInsideTheDualScreenFrame() = runComposeUiTest {
    launch(DualScreenLayout.Frame)
    assertFocused(tile(0, 0))
    press(listOf(Key.DirectionDown, Key.DirectionRight, Key.ButtonA))
    assertFocused("play")
    press(Key.ButtonB)
    assertFocused(tile(1, 1))
  }
}
