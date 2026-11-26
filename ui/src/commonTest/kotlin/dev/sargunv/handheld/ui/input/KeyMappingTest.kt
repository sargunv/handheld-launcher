package dev.sargunv.handheld.ui.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
  private val confirmA = InputSettings(confirmButton = ConfirmButton.A)
  private val confirmB = InputSettings(confirmButton = ConfirmButton.B)

  @Test
  fun faceButtonsFollowTheirKeyCodes() {
    assertEquals(PadButton.A, padButtonFor(Key.ButtonA))
    assertEquals(PadButton.B, padButtonFor(Key.ButtonB))
    assertEquals(PadButton.X, padButtonFor(Key.ButtonX))
    assertEquals(PadButton.Y, padButtonFor(Key.ButtonY))
    assertEquals(PadButton.DpadUp, padButtonFor(Key.DirectionUp))
    assertNull(padButtonFor(Key.Enter))
  }

  @Test
  fun confirmSettingSwapsAAndB() {
    assertEquals(Action.Confirm, confirmA.actionFor(Key.ButtonA))
    assertEquals(Action.Back, confirmA.actionFor(Key.ButtonB))
    assertEquals(Action.Back, confirmB.actionFor(Key.ButtonA))
    assertEquals(Action.Confirm, confirmB.actionFor(Key.ButtonB))
  }

  @Test
  fun aButtonPositionChangesNoAction() {
    for (settings in listOf(confirmA, confirmB)) {
      val aOnTheRight = settings.copy(aButtonPosition = AButtonPosition.East)
      for (button in PadButton.entries) {
        assertEquals(settings.actionFor(button), aOnTheRight.actionFor(button))
      }
    }
  }

  @Test
  fun confirmSettingLeavesTheOtherButtonsAlone() {
    for (settings in listOf(confirmA, confirmB)) {
      assertEquals(Action.Menu, settings.actionFor(Key.ButtonX))
      assertEquals(Action.PreviousShelf, settings.actionFor(Key.ButtonL1))
      assertEquals(Action.NextShelf, settings.actionFor(Key.ButtonR1))
      assertEquals(Action.MainMenu, settings.actionFor(Key.ButtonStart))
      assertEquals(Action.Select, settings.actionFor(Key.ButtonSelect))
      assertEquals(Action.Left, settings.actionFor(Key.DirectionLeft))
      assertNull(settings.actionFor(Key.ButtonL2))
    }
  }

  @Test
  fun keyboardKeysMeanActionsWhateverTheConfirmSetting() {
    for (settings in listOf(confirmA, confirmB)) {
      assertEquals(Action.Confirm, settings.actionFor(Key.Enter))
      assertEquals(Action.Back, settings.actionFor(Key.Escape))
      assertEquals(Action.Back, settings.actionFor(Key.Back))
      assertEquals(Action.Menu, settings.actionFor(Key.Tab))
      assertNull(settings.actionFor(Key.A))
    }
  }

  @Test
  fun onlyDirectionsRepeat() {
    assertEquals(
      setOf(Action.Up, Action.Down, Action.Left, Action.Right),
      Action.entries.filter { it.repeats }.toSet(),
    )
  }
}
