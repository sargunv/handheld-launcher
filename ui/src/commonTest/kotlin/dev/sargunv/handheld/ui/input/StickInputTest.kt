package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class StickDirectionTest {
  @Test
  fun deadZoneIsCentered() {
    val stick = StickDirection()
    assertNull(stick.update(0.2f, -0.3f))
  }

  @Test
  fun dominantAxisWins() {
    assertEquals(Direction.Right, StickDirection().update(0.8f, 0.4f))
    assertEquals(Direction.Up, StickDirection().update(0.3f, -0.9f))
  }

  @Test
  fun heldDirectionLastsUntilBelowTheReleaseThreshold() {
    val stick = StickDirection(pressThreshold = 0.5f, releaseThreshold = 0.3f)
    assertEquals(Direction.Left, stick.update(-0.6f, 0f))
    assertEquals(Direction.Left, stick.update(-0.4f, 0f))
    assertNull(stick.update(-0.2f, 0f))
    assertNull(stick.update(-0.4f, 0f))
  }

  @Test
  fun heldDirectionResistsADiagonalUntilTheOtherAxisTakesOver() {
    val stick = StickDirection()
    assertEquals(Direction.Down, stick.update(0f, 0.7f))
    assertEquals(Direction.Down, stick.update(0.45f, 0.6f))
    assertEquals(Direction.Right, stick.update(0.8f, 0.5f))
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DirectionRepeaterTest {
  @Test
  fun pressesOnceThenRepeatsWhileHeld() = runTest {
    val events = mutableListOf<String>()
    val repeater =
      DirectionRepeater(
        scope = backgroundScope,
        initialDelay = 400.milliseconds,
        interval = 100.milliseconds,
        onPress = { direction, repeat -> events += "$direction${if (repeat) "+" else ""}" },
        onRelease = { events += "^$it" },
      )

    repeater.hold(Direction.Right)
    runCurrent()
    assertEquals(listOf("Right"), events)

    advanceTimeBy(399.milliseconds)
    assertEquals(listOf("Right"), events)
    advanceTimeBy(2.milliseconds)
    assertEquals(listOf("Right", "Right+"), events)
    advanceTimeBy(200.milliseconds)
    assertEquals(listOf("Right", "Right+", "Right+", "Right+"), events)

    repeater.hold(Direction.Right)
    repeater.hold(null)
    advanceTimeBy(1000.milliseconds)
    assertEquals(listOf("Right", "Right+", "Right+", "Right+", "^Right"), events)
  }

  @Test
  fun changingDirectionReleasesTheOldOne() = runTest {
    val events = mutableListOf<String>()
    val repeater =
      DirectionRepeater(
        scope = backgroundScope,
        onPress = { direction, _ -> events += "$direction" },
        onRelease = { events += "^$it" },
      )

    repeater.hold(Direction.Up)
    repeater.hold(Direction.Left)
    runCurrent()
    assertEquals(listOf("Up", "^Up", "Left"), events)
  }
}
