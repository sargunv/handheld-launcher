package dev.sargunv.handheld.launch

import android.view.KeyEvent
import dev.sargunv.handheld.data.settings.AButtonPosition
import dev.sargunv.handheld.session.Button
import dev.sargunv.handheld.session.GamepadInput
import kotlin.test.Test
import kotlin.test.assertEquals

class FaceButtonsTest {
  /** The RetroPad button each face button's key code plays under [layout], by key code name. */
  private fun retroPad(layout: AButtonPosition): Map<String, Button?> {
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
  fun aAtTheBottomPlaysAsTheRetroPadsSouthButton() {
    // The RetroPad is positional: B south, A east, Y west, X north.
    assertEquals(
      mapOf("A" to Button.B, "B" to Button.A, "X" to Button.Y, "Y" to Button.X),
      retroPad(AButtonPosition.South),
    )
  }

  @Test
  fun aOnTheRightPlaysEachButtonAsItsName() {
    assertEquals(
      mapOf("A" to Button.A, "B" to Button.B, "X" to Button.X, "Y" to Button.Y),
      retroPad(AButtonPosition.East),
    )
  }
}
