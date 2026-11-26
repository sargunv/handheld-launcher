package dev.sargunv.handheld.session

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * Feeds Android key and motion events to a session's controller ports, mapped to the RetroPad by
 * position: the bottom face button, which Android calls A, is the RetroPad's B.
 *
 * Each gamepad takes the lowest free port as it connects, so the gamepads connected when the input
 * starts take ports in the order they connected, and a handheld's own controls, the only gamepad at
 * first, play on port 0. A gamepad's port frees when it disconnects. Keys from other devices, such
 * as a keyboard, go to port 0. [close] stops following connections.
 */
class GamepadInput(private val session: GameSession, private val inputManager: InputManager) :
  AutoCloseable {
  /** The port of each gamepad, by device ID. */
  private val ports = HashMap<Int, UInt>()
  private val hatX = IntArray(PORTS)
  private val hatY = IntArray(PORTS)

  private val listener =
    object : InputManager.InputDeviceListener {
      override fun onInputDeviceAdded(deviceId: Int) = connect(deviceId)

      override fun onInputDeviceRemoved(deviceId: Int) {
        ports.remove(deviceId)?.let(::release)
      }

      override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

  init {
    // Android numbers devices in the order they connect.
    inputManager.inputDeviceIds.sorted().forEach(::connect)
    inputManager.registerInputDeviceListener(listener, null)
  }

  override fun close() = inputManager.unregisterInputDeviceListener(listener)

  private fun connect(deviceId: Int) {
    val device = inputManager.getInputDevice(deviceId) ?: return
    if (deviceId in ports || !device.isGamepad()) return
    val port = (0u until PORTS.toUInt()).firstOrNull { it !in ports.values } ?: return
    ports[deviceId] = port
  }

  /** The port for events from [device], or null for a gamepad past the last port. */
  private fun portFor(device: InputDevice?): UInt? =
    when {
      device == null -> 0u
      device.id in ports -> ports[device.id]
      device.isGamepad() -> null
      else -> 0u
    }

  /** Applies a key event, and returns whether it was a RetroPad button. */
  fun onKeyEvent(event: KeyEvent): Boolean {
    val button = buttonFor(event.keyCode) ?: return false
    val port = portFor(event.device) ?: return true
    when (event.action) {
      KeyEvent.ACTION_DOWN -> session.setButton(port, button, true)
      KeyEvent.ACTION_UP -> session.setButton(port, button, false)
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
    val port = portFor(event.device) ?: return true
    session.setStick(
      port,
      Stick.LEFT,
      axis(event, MotionEvent.AXIS_X),
      axis(event, MotionEvent.AXIS_Y),
    )
    session.setStick(
      port,
      Stick.RIGHT,
      axis(event, MotionEvent.AXIS_Z),
      axis(event, MotionEvent.AXIS_RZ),
    )
    session.setTrigger(
      port,
      Button.L2,
      trigger(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
    )
    session.setTrigger(
      port,
      Button.R2,
      trigger(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
    )
    // Many gamepads report the D-pad as a hat rather than as keys.
    val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
    val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt()
    val i = port.toInt()
    if (x != hatX[i]) {
      session.setButton(port, Button.LEFT, x < 0)
      session.setButton(port, Button.RIGHT, x > 0)
      hatX[i] = x
    }
    if (y != hatY[i]) {
      session.setButton(port, Button.UP, y < 0)
      session.setButton(port, Button.DOWN, y > 0)
      hatY[i] = y
    }
    return true
  }

  /**
   * Releases every button, stick and trigger on every port. Call it when input stops arriving, such
   * as when the view loses focus, since a control released meanwhile sends this class no event.
   */
  fun releaseAll() {
    for (port in 0u until PORTS.toUInt()) release(port)
  }

  private fun release(port: UInt) {
    Button.entries.forEach { session.setButton(port, it, false) }
    session.setStick(port, Stick.LEFT, 0, 0)
    session.setStick(port, Stick.RIGHT, 0, 0)
    session.setTrigger(port, Button.L2, 0)
    session.setTrigger(port, Button.R2, 0)
    hatX[port.toInt()] = 0
    hatY[port.toInt()] = 0
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
    /** The ports the session has. */
    private const val PORTS = 4

    private fun InputDevice.isGamepad(): Boolean =
      !isVirtual &&
        (supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK))

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
