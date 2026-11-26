package dev.sargunv.handheld.ui.places

import dev.sargunv.handheld.ui.settings.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenTargetTest {
  @Test
  fun parsesEachKindOfTarget() {
    assertEquals(OpenTarget.AtPlace(Place.Downloads), OpenTarget.parse("place:downloads"))
    assertEquals(OpenTarget.AtPlace(Place.Home), OpenTarget.parse("place:Home"))
    assertEquals(OpenTarget.AtPlace(Place.Settings), OpenTarget.parse("place:settings"))
    assertEquals(OpenTarget.Game(42), OpenTarget.parse("game:42"))
    assertEquals(OpenTarget.LibraryShelf(null), OpenTarget.parse("library"))
    assertEquals(OpenTarget.LibraryShelf("gba"), OpenTarget.parse("library:gba"))
    assertEquals(OpenTarget.Settings(null), OpenTarget.parse("settings"))
    assertEquals(OpenTarget.Settings(SettingsSection.RomM), OpenTarget.parse("settings:romm"))
    assertEquals(
      OpenTarget.Settings(SettingsSection.RetroAchievements),
      OpenTarget.parse("settings:RetroAchievements"),
    )
  }

  @Test
  fun rejectsWhatNamesNoTarget() {
    assertNull(OpenTarget.parse("place:search"))
    assertNull(OpenTarget.parse("place"))
    assertNull(OpenTarget.parse("game:mario"))
    assertNull(OpenTarget.parse("game"))
    assertNull(OpenTarget.parse("settings:display"))
  }
}
