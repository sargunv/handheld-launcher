package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals

class AButtonPositionTest {
  @Test
  fun southPutsBEast() {
    val layout = AButtonPosition.South
    assertEquals(PadButton.A, layout.buttonAt(FacePosition.South))
    assertEquals(PadButton.B, layout.buttonAt(FacePosition.East))
    assertEquals(PadButton.X, layout.buttonAt(FacePosition.West))
    assertEquals(PadButton.Y, layout.buttonAt(FacePosition.North))
  }

  @Test
  fun eastPutsBSouth() {
    val layout = AButtonPosition.East
    assertEquals(PadButton.A, layout.buttonAt(FacePosition.East))
    assertEquals(PadButton.B, layout.buttonAt(FacePosition.South))
    assertEquals(PadButton.X, layout.buttonAt(FacePosition.North))
    assertEquals(PadButton.Y, layout.buttonAt(FacePosition.West))
  }
}
