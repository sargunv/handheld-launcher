package dev.sargunv.handheld.launch

import android.view.KeyEvent
import dev.sargunv.handheld.session.Button
import dev.sargunv.handheld.session.FaceKeyCodes
import dev.sargunv.handheld.session.GamepadInput
import dev.sargunv.handheld.ui.input.FaceButtonLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class FaceButtonsTest {
  /** The RetroPad button each face button's key code plays under [layout], by key code name. */
  private fun retroPad(layout: FaceButtonLayout): Map<String, Button?> {
    val keyCodes = layout.faceKeyCodes()
    return mapOf(
        "A" to KeyEvent.KEYCODE_BUTTON_A,
        "B" to KeyEvent.KEYCODE_BUTTON_B,
        "X" to KeyEvent.KEYCODE_BUTTON_X,
        "Y" to KeyEvent.KEYCODE_BUTTON_Y,
      )
      .mapValues { (_, keyCode) -> GamepadInput.buttonFor(keyCode, keyCodes) }
  }

  @Test
  fun xboxLayoutPlaysAAsTheRetroPadsSouthButton() {
    // The RetroPad is positional: B south, A east, Y west, X north.
    assertEquals(
      mapOf("A" to Button.B, "B" to Button.A, "X" to Button.Y, "Y" to Button.X),
      retroPad(FaceButtonLayout.Xbox),
    )
  }

  @Test
  fun nintendoLayoutPlaysEachButtonAsItsName() {
    assertEquals(
      mapOf("A" to Button.A, "B" to Button.B, "X" to Button.X, "Y" to Button.Y),
      retroPad(FaceButtonLayout.Nintendo),
    )
  }

  @Test
  fun xboxLayoutIsAndroidsDefault() {
    assertEquals(FaceKeyCodes.ANDROID_DEFAULT, FaceButtonLayout.Xbox.faceKeyCodes())
  }

  @Test
  fun layoutLeavesTheOtherButtonsAlone() {
    for (layout in FaceButtonLayout.entries) {
      val keyCodes = layout.faceKeyCodes()
      assertEquals(Button.START, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_START, keyCodes))
      assertEquals(Button.L, GamepadInput.buttonFor(KeyEvent.KEYCODE_BUTTON_L1, keyCodes))
      assertEquals(Button.UP, GamepadInput.buttonFor(KeyEvent.KEYCODE_DPAD_UP, keyCodes))
      assertEquals(null, GamepadInput.buttonFor(KeyEvent.KEYCODE_ENTER, keyCodes))
    }
  }
}
