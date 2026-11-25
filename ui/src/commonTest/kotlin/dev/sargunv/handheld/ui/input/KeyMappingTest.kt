package dev.sargunv.handheld.ui.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
  private val south = InputSettings(confirmButton = ConfirmButton.South)
  private val east = InputSettings(confirmButton = ConfirmButton.East)

  @Test
  fun gamepadButtonsArePositional() {
    assertEquals(PadButton.South, padButtonFor(Key.ButtonA))
    assertEquals(PadButton.East, padButtonFor(Key.ButtonB))
    assertEquals(PadButton.West, padButtonFor(Key.ButtonX))
    assertEquals(PadButton.North, padButtonFor(Key.ButtonY))
    assertEquals(PadButton.DpadUp, padButtonFor(Key.DirectionUp))
    assertNull(padButtonFor(Key.Enter))
  }

  @Test
  fun confirmSettingSwapsTheSouthAndEastButtons() {
    assertEquals(Action.Confirm, south.actionFor(Key.ButtonA))
    assertEquals(Action.Back, south.actionFor(Key.ButtonB))
    assertEquals(Action.Back, east.actionFor(Key.ButtonA))
    assertEquals(Action.Confirm, east.actionFor(Key.ButtonB))
  }

  @Test
  fun confirmSettingLeavesTheOtherButtonsAlone() {
    for (settings in listOf(south, east)) {
      assertEquals(Action.Menu, settings.actionFor(Key.ButtonX))
      assertEquals(Action.Search, settings.actionFor(Key.ButtonY))
      assertEquals(Action.PreviousShelf, settings.actionFor(Key.ButtonL1))
      assertEquals(Action.NextShelf, settings.actionFor(Key.ButtonR1))
      assertEquals(Action.QuickMenu, settings.actionFor(Key.ButtonStart))
      assertEquals(Action.Select, settings.actionFor(Key.ButtonSelect))
      assertEquals(Action.Left, settings.actionFor(Key.DirectionLeft))
      assertNull(settings.actionFor(Key.ButtonL2))
    }
  }

  @Test
  fun keyboardKeysMeanActionsWhateverTheConfirmSetting() {
    for (settings in listOf(south, east)) {
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
