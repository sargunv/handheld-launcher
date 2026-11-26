package dev.sargunv.handheld.domain.achievements

import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertNull

class RetroAchievementsConsolesTest {
  @Test
  fun platformsWithoutAchievementsHaveNoConsole() {
    assertNull(retroAchievementsConsole(PlatformCatalog.SWITCH))
    assertNull(retroAchievementsConsole(PlatformSlug("android")))
  }
}
