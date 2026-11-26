package dev.sargunv.handheld.launch

import android.view.KeyEvent
import dev.sargunv.handheld.session.FaceKeyCodes
import dev.sargunv.handheld.ui.input.FaceButtonLayout
import dev.sargunv.handheld.ui.input.FacePosition
import dev.sargunv.handheld.ui.input.PadButton

/** The key code each face button position sends under this layout, for the session's RetroPad. */
internal fun FaceButtonLayout.faceKeyCodes(): FaceKeyCodes =
  FaceKeyCodes(
    south = keyCodeOf(buttonAt(FacePosition.South)),
    east = keyCodeOf(buttonAt(FacePosition.East)),
    west = keyCodeOf(buttonAt(FacePosition.West)),
    north = keyCodeOf(buttonAt(FacePosition.North)),
  )

private fun keyCodeOf(faceButton: PadButton): Int =
  when (faceButton) {
    PadButton.A -> KeyEvent.KEYCODE_BUTTON_A
    PadButton.B -> KeyEvent.KEYCODE_BUTTON_B
    PadButton.X -> KeyEvent.KEYCODE_BUTTON_X
    PadButton.Y -> KeyEvent.KEYCODE_BUTTON_Y
    else -> throw IllegalArgumentException("$faceButton isn't a face button")
  }
