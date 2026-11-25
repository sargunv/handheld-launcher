package dev.sargunv.handheld.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals

class ComfortableBringIntoViewSpecTest {
  private val spec = ComfortableBringIntoViewSpec

  @Test
  fun itemWellInsideTheViewportDoesntScroll() {
    assertEquals(
      0f,
      spec.calculateScrollDistance(offset = 300f, size = 100f, containerSize = 1000f),
    )
  }

  @Test
  fun itemNearTheEndScrollsToLeaveAMargin() {
    // The item ends at 950; with a 200 margin it should end at 800.
    assertEquals(
      150f,
      spec.calculateScrollDistance(offset = 850f, size = 100f, containerSize = 1000f),
    )
  }

  @Test
  fun itemNearTheStartScrollsToLeaveAMargin() {
    assertEquals(
      -150f,
      spec.calculateScrollDistance(offset = 50f, size = 100f, containerSize = 1000f),
    )
  }

  @Test
  fun marginShrinksForItemsTooBigToHaveIt() {
    // An 800 item in a 1000 viewport only has room for 100 on each side, so one ending at 1050
    // scrolls to end at 900.
    assertEquals(
      150f,
      spec.calculateScrollDistance(offset = 250f, size = 800f, containerSize = 1000f),
    )
  }
}
