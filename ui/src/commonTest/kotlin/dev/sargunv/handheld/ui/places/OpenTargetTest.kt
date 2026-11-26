package dev.sargunv.handheld.ui.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenTargetTest {
  @Test
  fun parsesEachKindOfTarget() {
    assertEquals(OpenTarget.AtPlace(Place.Downloads), OpenTarget.parse("place:downloads"))
    assertEquals(OpenTarget.AtPlace(Place.Home), OpenTarget.parse("place:Home"))
    assertEquals(OpenTarget.Game(42), OpenTarget.parse("game:42"))
    assertEquals(OpenTarget.LibraryShelf(null), OpenTarget.parse("library"))
    assertEquals(OpenTarget.LibraryShelf("gba"), OpenTarget.parse("library:gba"))
    assertEquals(OpenTarget.About, OpenTarget.parse("about"))
  }

  @Test
  fun rejectsWhatNamesNoTarget() {
    assertNull(OpenTarget.parse("place:settings"))
    assertNull(OpenTarget.parse("place"))
    assertNull(OpenTarget.parse("game:mario"))
    assertNull(OpenTarget.parse("game"))
    assertNull(OpenTarget.parse("about:licences"))
    assertNull(OpenTarget.parse("settings"))
  }
}
