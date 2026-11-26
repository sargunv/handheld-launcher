package dev.sargunv.handheld.domain.achievements

import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetroAchievementsConsolesTest {
  @Test
  fun mapsPlatformsToConsoles() {
    assertEquals(4, retroAchievementsConsole(PlatformCatalog.GB))
    assertEquals(6, retroAchievementsConsole(PlatformCatalog.GBC))
    assertEquals(5, retroAchievementsConsole(PlatformCatalog.GBA))
    assertEquals(7, retroAchievementsConsole(PlatformCatalog.NES))
    // Regional names share their console.
    assertEquals(7, retroAchievementsConsole(PlatformCatalog.FAMICOM))
    assertEquals(3, retroAchievementsConsole(PlatformCatalog.SUPER_FAMICOM))
  }

  @Test
  fun platformsWithoutAchievementsHaveNoConsole() {
    assertNull(retroAchievementsConsole(PlatformCatalog.SWITCH))
    assertNull(retroAchievementsConsole(PlatformSlug("android")))
  }
}
