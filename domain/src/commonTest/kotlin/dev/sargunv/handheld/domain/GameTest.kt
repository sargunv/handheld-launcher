package dev.sargunv.handheld.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class GameTest {
  @Test
  fun equalityIsByValue() {
    assertEquals(Game(1, "Jak and Daxter", "ps2"), Game(1, "Jak and Daxter", "ps2"))
  }
}
