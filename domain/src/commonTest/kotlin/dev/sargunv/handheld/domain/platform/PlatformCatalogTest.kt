package dev.sargunv.handheld.domain.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCatalogTest {
  @Test
  fun twinsRunAsTheirPlatform() {
    assertEquals(PlatformCatalog.NES, PlatformCatalog.emulatedAs(PlatformCatalog.FAMICOM))
    assertEquals(PlatformCatalog.SNES, PlatformCatalog.emulatedAs(PlatformCatalog.SUPER_FAMICOM))
    assertEquals(PlatformCatalog.PS2, PlatformCatalog.emulatedAs(PlatformCatalog.PS2))
  }

  @Test
  fun anUnknownPlatformRunsAsItself() {
    assertEquals(PlatformSlug("virtualboy"), PlatformCatalog.emulatedAs(PlatformSlug("virtualboy")))
  }
}
