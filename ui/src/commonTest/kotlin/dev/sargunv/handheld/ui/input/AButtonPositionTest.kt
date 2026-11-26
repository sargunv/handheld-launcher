package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

  @Test
  fun positionOfFindsWhereEachButtonSits() {
    for (layout in AButtonPosition.entries) {
      for (position in FacePosition.entries) {
        assertEquals(position, layout.positionOf(layout.buttonAt(position)))
      }
    }
    assertEquals(FacePosition.East, AButtonPosition.East.positionOf(PadButton.A))
    assertEquals(FacePosition.South, AButtonPosition.South.positionOf(PadButton.A))
  }

  @Test
  fun otherButtonsHaveNoFacePosition() {
    for (layout in AButtonPosition.entries) {
      assertNull(layout.positionOf(PadButton.Start))
      assertNull(layout.positionOf(PadButton.DpadUp))
    }
  }
}
