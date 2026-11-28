package dev.sargunv.handheld.ui.input

import androidx.compose.ui.input.key.Key
import dev.sargunv.handheld.data.settings.ConfirmButton
import dev.sargunv.handheld.data.settings.InputSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
  private val confirmA = InputSettings(confirmButton = ConfirmButton.A)
  private val confirmB = InputSettings(confirmButton = ConfirmButton.B)

  @Test
  fun confirmSettingSwapsAAndB() {
    assertEquals(Action.Confirm, confirmA.actionFor(Key.ButtonA))
    assertEquals(Action.Back, confirmA.actionFor(Key.ButtonB))
    assertEquals(Action.Back, confirmB.actionFor(Key.ButtonA))
    assertEquals(Action.Confirm, confirmB.actionFor(Key.ButtonB))
  }

  @Test
  fun confirmSettingLeavesTheOtherButtonsAlone() {
    for (settings in listOf(confirmA, confirmB)) {
      assertEquals(Action.Menu, settings.actionFor(Key.ButtonX))
      assertEquals(Action.PreviousShelf, settings.actionFor(Key.ButtonL1))
      assertEquals(Action.NextShelf, settings.actionFor(Key.ButtonR1))
      assertEquals(Action.MainMenu, settings.actionFor(Key.ButtonStart))
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
}
