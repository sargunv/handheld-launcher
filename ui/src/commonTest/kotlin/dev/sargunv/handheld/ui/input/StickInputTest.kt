package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    val stick = StickDirection()
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

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerDirectionsTest {
  private fun TestScope.directions(events: MutableList<String>) =
    ControllerDirections(
      scope = backgroundScope,
      onPress = { direction, repeat -> events += "$direction${if (repeat) "+" else ""}" },
      onRelease = { events += "^$it" },
    )

  @Test
  fun removingTheControllerThatHoldsADirectionStopsItsRepeats() = runTest {
    val events = mutableListOf<String>()
    val directions = directions(events)
    directions.move(device = 7, hatX = 0f, hatY = 0f, stickX = 1f, stickY = 0f)
    runCurrent()

    directions.deviceRemoved(7)
    advanceTimeBy(1000.milliseconds)
    assertEquals(listOf("Right", "^Right"), events)

    // The stick that was held doesn't come back as held when input resumes.
    directions.move(device = 8, hatX = 0f, hatY = 0f, stickX = 0.4f, stickY = 0f)
    runCurrent()
    assertEquals(listOf("Right", "^Right"), events)
  }

  @Test
  fun removingAnotherControllerKeepsTheDirection() = runTest {
    val events = mutableListOf<String>()
    val directions = directions(events)
    directions.move(device = 7, hatX = 0f, hatY = -1f, stickX = 0f, stickY = 0f)
    runCurrent()

    directions.deviceRemoved(8)
    advanceTimeBy(401.milliseconds)
    assertEquals(listOf("Up", "Up+"), events)
  }
}
