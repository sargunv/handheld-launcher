package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaceButtonLayoutTest {
  @Test
  fun xboxPutsASouth() {
    val layout = FaceButtonLayout.Xbox
    assertEquals(PadButton.A, layout.buttonAt(FacePosition.South))
    assertEquals(PadButton.B, layout.buttonAt(FacePosition.East))
    assertEquals(PadButton.X, layout.buttonAt(FacePosition.West))
    assertEquals(PadButton.Y, layout.buttonAt(FacePosition.North))
  }

  @Test
  fun nintendoPutsAEast() {
    val layout = FaceButtonLayout.Nintendo
    assertEquals(PadButton.A, layout.buttonAt(FacePosition.East))
    assertEquals(PadButton.B, layout.buttonAt(FacePosition.South))
    assertEquals(PadButton.X, layout.buttonAt(FacePosition.North))
    assertEquals(PadButton.Y, layout.buttonAt(FacePosition.West))
  }

  @Test
  fun positionOfFindsWhereEachButtonSits() {
    for (layout in FaceButtonLayout.entries) {
      for (position in FacePosition.entries) {
        assertEquals(position, layout.positionOf(layout.buttonAt(position)))
      }
    }
    assertEquals(FacePosition.East, FaceButtonLayout.Nintendo.positionOf(PadButton.A))
    assertEquals(FacePosition.South, FaceButtonLayout.Xbox.positionOf(PadButton.A))
  }

  @Test
  fun otherButtonsHaveNoFacePosition() {
    for (layout in FaceButtonLayout.entries) {
      assertNull(layout.positionOf(PadButton.Start))
      assertNull(layout.positionOf(PadButton.DpadUp))
    }
  }
}
