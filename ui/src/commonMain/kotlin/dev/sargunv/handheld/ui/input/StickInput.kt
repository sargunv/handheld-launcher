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
