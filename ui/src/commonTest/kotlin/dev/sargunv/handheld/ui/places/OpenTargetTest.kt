package dev.sargunv.handheld.ui.places

import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.ui.library.LibraryTab
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
    assertEquals(
      OpenTarget.Local("/storage/emulated/0/ROMs/gb/My: Game.gb"),
      OpenTarget.parse("local:/storage/emulated/0/ROMs/gb/My: Game.gb"),
    )
    assertEquals(OpenTarget.Library(null), OpenTarget.parse("library"))
    assertEquals(OpenTarget.Library(LibraryTab.OnDevice), OpenTarget.parse("library:tab:on-device"))
    assertEquals(OpenTarget.Library(LibraryTab.AllGames), OpenTarget.parse("library:tab:AllGames"))
    assertEquals(OpenTarget.LibraryPlatform(PlatformCatalog.GBA), OpenTarget.parse("library:gba"))
    assertEquals(
      OpenTarget.LibraryCollection(CollectionKind.Regular, 7),
      OpenTarget.parse("library:collection:7"),
    )
    assertEquals(
      OpenTarget.LibraryCollection(CollectionKind.Smart, 3),
      OpenTarget.parse("library:collection:smart:3"),
    )
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
    assertNull(OpenTarget.parse("local:ROMs/gb/Game.gb"))
    assertNull(OpenTarget.parse("settings:display"))
    assertNull(OpenTarget.parse("library:tab:search"))
    assertNull(OpenTarget.parse("library:collection:favourites"))
  }
}
