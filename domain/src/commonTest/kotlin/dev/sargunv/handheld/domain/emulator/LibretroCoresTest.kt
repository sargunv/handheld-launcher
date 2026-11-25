package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibretroCoresTest {
  @Test
  fun everyPlatformHasAnExplicitEntry() {
    val platforms = PlatformCatalog.all.filter { it.runsAs == null }.map { it.slug }.toSet()
    assertEquals(platforms, LibretroCores.recommended.keys)
  }

  @Test
  fun namesAreBuildbotLibraryNames() {
    for (core in LibretroCores.recommended.values.filterNotNull()) {
      assertTrue(core.library.endsWith("_libretro"), core.library)
      assertTrue(core.androidLibrary.endsWith("_libretro"), core.androidLibrary)
    }
  }

  @Test
  fun twinsUseTheirPlatformsCore() {
    assertEquals(
      LibretroCores.recommendedFor(PlatformCatalog.SNES),
      LibretroCores.recommendedFor(PlatformCatalog.SUPER_FAMICOM),
    )
    assertEquals("snes9x_libretro", LibretroCores.recommendedFor(PlatformCatalog.SNES)?.library)
  }

  @Test
  fun standaloneOnlyPlatformsHaveNoCore() {
    assertNull(LibretroCores.recommendedFor(PlatformCatalog.PS2))
    assertNull(LibretroCores.recommendedFor(PlatformCatalog.SWITCH))
  }
}
