package dev.sargunv.handheld.ui.input

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads a direction from an analog stick or hat, with hysteresis so a stick resting near the
 * threshold doesn't chatter.
 *
 * Axes run from -1 to 1, with positive x right and positive y down.
 *
 * @param pressThreshold how far the stick has to lean to start a direction.
 * @param releaseThreshold how far back it has to come to end one. Must not exceed [pressThreshold].
 */
class StickDirection(
  private val pressThreshold: Float = 0.5f,
  private val releaseThreshold: Float = 0.3f,
) {
  init {
    require(releaseThreshold <= pressThreshold)
  }

  /** The direction the stick points in now, or null when it's centered. */
  var current: Direction? = null
    private set

  /** Takes a new stick position and returns the direction it points in. */
  fun update(x: Float, y: Float): Direction? {
    val held = current
    if (held != null && lean(held, x, y) >= releaseThreshold && !otherAxisDominates(held, x, y)) {
      return held
    }
    current =
      when {
        maxOf(abs(x), abs(y)) < pressThreshold -> null
        abs(x) > abs(y) -> if (x < 0) Direction.Left else Direction.Right
        else -> if (y < 0) Direction.Up else Direction.Down
      }
    return current
  }

  /** Forgets the held direction, as if the stick were centered. */
  fun reset() {
    current = null
  }

  private fun lean(direction: Direction, x: Float, y: Float): Float =
    when (direction) {
      Direction.Left -> -x
      Direction.Right -> x
      Direction.Up -> -y
      Direction.Down -> y
    }

  private fun otherAxisDominates(direction: Direction, x: Float, y: Float): Boolean =
    when (direction) {
      Direction.Left,
      Direction.Right -> abs(y) >= pressThreshold && abs(y) > abs(x)
      Direction.Up,
      Direction.Down -> abs(x) >= pressThreshold && abs(x) > abs(y)
    }
}

/**
 * Turns a held direction into presses: one at once, then repeats while it stays held, like a held
 * key.
 *
 * @param onPress called for each press; `repeat` is false for the first.
 * @param onRelease called when the held direction ends or changes.
 */
class DirectionRepeater(
  private val scope: CoroutineScope,
  private val initialDelay: Duration = 400.milliseconds,
  private val interval: Duration = 100.milliseconds,
  private val onPress: (direction: Direction, repeat: Boolean) -> Unit,
  private val onRelease: (direction: Direction) -> Unit,
) {
  private var held: Direction? = null
  private var repeating: Job? = null

  /**
   * Holds [direction], or releases the held one when null. Holding the same one again is a no-op.
   */
  fun hold(direction: Direction?) {
    if (direction == held) return
    repeating?.cancel()
    repeating = null
    held?.let(onRelease)
    held = direction
    if (direction == null) return
    onPress(direction, false)
    repeating = scope.launch {
      delay(initialDelay)
      while (true) {
        onPress(direction, true)
        delay(interval)
      }
    }
  }
}

/**
 * Holds one direction at a time from controllers' hats and left sticks, with key repeat. A leaning
 * hat wins over the stick. The direction ends when the controller that last moved goes away.
 *
 * @param onPress called for each press; `repeat` is false for the first.
 * @param onRelease called when the held direction ends or changes.
 */
class ControllerDirections(
  scope: CoroutineScope,
  initialDelay: Duration = 400.milliseconds,
  interval: Duration = 100.milliseconds,
  onPress: (direction: Direction, repeat: Boolean) -> Unit,
  onRelease: (direction: Direction) -> Unit,
) {
  private val stick = StickDirection()
  private val hat = StickDirection()
  private var device: Int? = null
  private val repeater = DirectionRepeater(scope, initialDelay, interval, onPress, onRelease)

  /** Takes the positions of [device]'s hat and left stick. */
  fun move(device: Int, hatX: Float, hatY: Float, stickX: Float, stickY: Float) {
    this.device = device
    val fromHat = hat.update(hatX, hatY)
    val fromStick = stick.update(stickX, stickY)
    repeater.hold(fromHat ?: fromStick)
  }

  /** Ends the held direction, as when the window loses focus or the controller's input stops. */
  fun release() {
    device = null
    stick.reset()
    hat.reset()
    repeater.hold(null)
  }

  /** Ends the held direction if [device], which has gone away, was the last to move. */
  fun deviceRemoved(device: Int) {
    if (device == this.device) release()
  }
}
