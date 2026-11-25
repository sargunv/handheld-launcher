package dev.sargunv.handheld.session

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * Feeds Android key and motion events to a session's controller port 0, mapped to the RetroPad by
 * position: the bottom face button, which Android calls A, is the RetroPad's B.
 */
class GamepadInput(private val session: GameSession) {
  private var hatX = 0
  private var hatY = 0

  /** Applies a key event, and returns whether it was a RetroPad button. */
  fun onKeyEvent(event: KeyEvent): Boolean {
    val button = buttonFor(event.keyCode) ?: return false
    when (event.action) {
      KeyEvent.ACTION_DOWN -> session.setButton(PORT, button, true)
      KeyEvent.ACTION_UP -> session.setButton(PORT, button, false)
    }
    return true
  }

  /** Applies a joystick motion event, and returns whether it came from a gamepad or joystick. */
  fun onMotionEvent(event: MotionEvent): Boolean {
    if (
      !event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE
    ) {
      return false
    }
    session.setStick(
      PORT,
      Stick.LEFT,
      axis(event, MotionEvent.AXIS_X),
      axis(event, MotionEvent.AXIS_Y),
    )
    session.setStick(
      PORT,
      Stick.RIGHT,
      axis(event, MotionEvent.AXIS_Z),
      axis(event, MotionEvent.AXIS_RZ),
    )
    session.setTrigger(
      PORT,
      Button.L2,
      trigger(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
    )
    session.setTrigger(
      PORT,
      Button.R2,
      trigger(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
    )
    // Many gamepads report the D-pad as a hat rather than as keys.
    val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
    val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt()
    if (x != hatX) {
      session.setButton(PORT, Button.LEFT, x < 0)
      session.setButton(PORT, Button.RIGHT, x > 0)
      hatX = x
    }
    if (y != hatY) {
      session.setButton(PORT, Button.UP, y < 0)
      session.setButton(PORT, Button.DOWN, y > 0)
      hatY = y
    }
    return true
  }

  /**
   * Releases every button, stick and trigger. Call it when input stops arriving, such as when the
   * view loses focus, since a control released meanwhile sends this class no event.
   */
  fun releaseAll() {
    Button.entries.forEach { session.setButton(PORT, it, false) }
    session.setStick(PORT, Stick.LEFT, 0, 0)
    session.setStick(PORT, Stick.RIGHT, 0, 0)
    session.setTrigger(PORT, Button.L2, 0)
    session.setTrigger(PORT, Button.R2, 0)
    hatX = 0
    hatY = 0
  }

  private fun axis(event: MotionEvent, axis: Int): Short {
    val range = event.device?.getMotionRange(axis, event.source)
    var value = event.getAxisValue(axis)
    if (range != null && kotlin.math.abs(value) < range.flat) value = 0f
    return (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
  }

  private fun trigger(event: MotionEvent, axis: Int, fallback: Int): Short {
    val value = maxOf(event.getAxisValue(axis), event.getAxisValue(fallback))
    return (value.coerceIn(0f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
  }

  companion object {
    private const val PORT = 0u

    /** The RetroPad button an Android key code stands for, if any. */
    fun buttonFor(keyCode: Int): Button? =
      when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Button.B
        KeyEvent.KEYCODE_BUTTON_B -> Button.A
        KeyEvent.KEYCODE_BUTTON_X -> Button.Y
        KeyEvent.KEYCODE_BUTTON_Y -> Button.X
        KeyEvent.KEYCODE_BUTTON_L1 -> Button.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Button.R
        KeyEvent.KEYCODE_BUTTON_L2 -> Button.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Button.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Button.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Button.R3
        KeyEvent.KEYCODE_BUTTON_START -> Button.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Button.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Button.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Button.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Button.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Button.RIGHT
        else -> null
      }
  }
}
