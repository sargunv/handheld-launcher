package dev.sargunv.handheld.domain.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformCatalogTest {
  @Test
  fun coversRommSlugs() {
    val rommSlugs =
      listOf(
        "nes",
        "famicom",
        "fds",
        "snes",
        "sfam",
        "n64",
        "64dd",
        "gb",
        "gbc",
        "gba",
        "nds",
        "3ds",
        "new-nintendo-3ds",
        "ngc",
        "wii",
        "wiiu",
        "switch",
        "psx",
        "ps2",
        "ps3",
        "psp",
        "psvita",
        "genesis",
        "sms",
        "gamegear",
        "segacd",
        "sega32",
        "saturn",
        "dc",
        "tg16",
        "turbografx-cd",
        "pokemon-mini",
        "xbox",
        "arcade",
      )
    for (slug in rommSlugs) assertEquals(slug, PlatformCatalog[slug]?.slug?.value, slug)
  }

  @Test
  fun resolvesFolderNames() {
    val aliases =
      mapOf(
        "sfc" to "sfam",
        "n64dd" to "64dd",
        "n3ds" to "3ds",
        "gc" to "ngc",
        "mastersystem" to "sms",
        "sega32x" to "sega32",
        "dreamcast" to "dc",
        "tg-cd" to "turbografx-cd",
        "pokemini" to "pokemon-mini",
        "megadrive" to "genesis",
      )
    for ((alias, slug) in aliases) assertEquals(slug, PlatformCatalog[alias]?.slug?.value, alias)
  }

  @Test
  fun ignoresCase() {
    assertEquals(PlatformCatalog.PS2, PlatformCatalog["PS2"]?.slug)
  }

  @Test
  fun unknownSlugIsNull() {
    assertNull(PlatformCatalog["virtualboy"])
    assertEquals(PlatformSlug("virtualboy"), PlatformCatalog.emulatedAs(PlatformSlug("virtualboy")))
  }

  @Test
  fun namesAreUnique() {
    val names = PlatformCatalog.all.flatMap { it.aliases + it.slug.value }
    assertEquals(names.size, names.toSet().size, "a name belongs to two platforms")
  }

  @Test
  fun twinsRunAsPlatformsThatRunAsThemselves() {
    for (platform in PlatformCatalog.all) {
      val runsAs = platform.runsAs ?: continue
      assertNull(PlatformCatalog[runsAs]?.runsAs, "${platform.slug} runs as a twin")
    }
    assertEquals(PlatformCatalog.NES, PlatformCatalog.emulatedAs(PlatformCatalog.FAMICOM))
    assertEquals(PlatformCatalog.SNES, PlatformCatalog.emulatedAs(PlatformCatalog.SUPER_FAMICOM))
  }

  @Test
  fun everyPlatformHasADisplayName() {
    assertTrue(PlatformCatalog.all.all { it.displayName.isNotBlank() })
  }
}
