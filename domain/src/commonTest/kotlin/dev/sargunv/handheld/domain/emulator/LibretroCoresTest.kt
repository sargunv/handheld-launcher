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

  @Test
  fun anOverrideReplacesTheRecommendedCore() {
    val overrides = mapOf(PlatformCatalog.GB to "mgba_libretro")
    assertEquals("mgba_libretro", LibretroCores.coreFor(PlatformCatalog.GB, overrides)?.library)
    assertEquals(
      "gambatte_libretro",
      LibretroCores.coreFor(PlatformCatalog.GBC, overrides)?.library,
    )
  }

  @Test
  fun anOverrideNamingAnUnknownCoreFallsBackToTheRecommendedOne() {
    val overrides = mapOf(PlatformCatalog.GB to "nonexistent_libretro")
    assertEquals(
      "gambatte_libretro",
      LibretroCores.coreFor(PlatformCatalog.GB, overrides)?.library,
    )
  }

  @Test
  fun findsAReleaseAssetForAnAbi() {
    val source = LibretroCores.byLibrary("azahar_libretro")?.source
    assertTrue(source is CoreSource.GitHubRelease)
    val assets =
      listOf(
        "azahar-libretro-ios-arm64-2127.0.1.zip",
        "azahar-libretro-android-arm64-v8a-2127.0.1.zip",
        "azahar-2127.0.1.tar.xz",
      )
    assertEquals(
      "azahar-libretro-android-arm64-v8a-2127.0.1.zip",
      source.assetFor("arm64-v8a", assets),
    )
    assertNull(source.assetFor("x86_64", assets))
  }
}
