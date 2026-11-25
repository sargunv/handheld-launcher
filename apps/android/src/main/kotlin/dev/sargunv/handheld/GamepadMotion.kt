package dev.sargunv.handheld

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import dev.sargunv.handheld.ui.input.ControllerDirections
import dev.sargunv.handheld.ui.input.Direction
import kotlinx.coroutines.CoroutineScope

/**
 * Turns the left stick and the hat (a d-pad some gamepads report as axes) into d-pad key events,
 * with key repeat, so every input reaches the UI the same way the d-pad's keys do. Register it as
 * an input device listener, so a controller that goes away mid-hold stops repeating.
 *
 * @param dispatch delivers a synthesized key event to the window.
 */
class GamepadMotion(scope: CoroutineScope, private val dispatch: (KeyEvent) -> Unit) :
  InputManager.InputDeviceListener {
  private var downTime = 0L
  private var repeatCount = 0

  private val directions =
    ControllerDirections(
      scope = scope,
      onPress = { direction, repeat ->
        val now = SystemClock.uptimeMillis()
        if (!repeat) {
          downTime = now
          repeatCount = 0
        } else {
          repeatCount++
        }
        dispatch(keyEvent(KeyEvent.ACTION_DOWN, direction, now, repeatCount))
      },
      onRelease = { direction ->
        dispatch(keyEvent(KeyEvent.ACTION_UP, direction, SystemClock.uptimeMillis(), 0))
      },
    )

  /** Takes a generic motion event. Returns true if it came from a joystick and was used. */
  fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_MOVE ->
        directions.move(
          device = event.deviceId,
          hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
          hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
          stickX = event.getAxisValue(MotionEvent.AXIS_X),
          stickY = event.getAxisValue(MotionEvent.AXIS_Y),
        )
      MotionEvent.ACTION_CANCEL -> directions.release()
      else -> return false
    }
    return true
  }

  /** Releases the held direction, as when the window loses focus. */
  fun release() = directions.release()

  override fun onInputDeviceRemoved(deviceId: Int) = directions.deviceRemoved(deviceId)

  override fun onInputDeviceAdded(deviceId: Int) = Unit

  override fun onInputDeviceChanged(deviceId: Int) = Unit

  private fun keyEvent(action: Int, direction: Direction, eventTime: Long, repeat: Int) =
    KeyEvent(
      downTime,
      eventTime,
      action,
      direction.keyCode,
      repeat,
      0,
      KeyCharacterMap.VIRTUAL_KEYBOARD,
      0,
      0,
      InputDevice.SOURCE_DPAD,
    )

  private val Direction.keyCode: Int
    get() =
      when (this) {
        Direction.Up -> KeyEvent.KEYCODE_DPAD_UP
        Direction.Down -> KeyEvent.KEYCODE_DPAD_DOWN
        Direction.Left -> KeyEvent.KEYCODE_DPAD_LEFT
        Direction.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
      }
}
