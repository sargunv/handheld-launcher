package dev.sargunv.handheld.domain.saves

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveKnowledgeTest {
  private val placeholder = Regex("""\{\w+}""")

  private fun assertKnownPlaceholders(owner: String, paths: List<String>) {
    for (path in paths) {
      assertTrue(path.startsWith("/") || path.startsWith("{"), "$owner: $path isn't absolute")
      assertTrue(!path.endsWith("/"), "$owner: $path ends with a slash")
      for (match in placeholder.findAll(path)) {
        assertTrue(match.value in SavePaths.all, "$owner: ${match.value} in $path")
      }
    }
  }

  private fun assertKnownEmulator(id: EmulatorId) {
    assertNotNull(EmulatorRegistry[id], "$id isn't a known emulator")
  }

  @Test
  fun saveLocationsAreWellFormed() {
    for (location in SaveLocations.all) {
      assertKnownEmulator(location.emulator)
      assertKnownPlaceholders(location.emulator.value, location.paths)
      if (location.layout !is SaveLayout.BesideRom) {
        assertTrue(location.paths.isNotEmpty(), "${location.emulator} has no save folder")
      }
      val emulator = assertNotNull(EmulatorRegistry[location.emulator])
      for (platform in location.platforms.orEmpty()) {
        assertTrue(
          emulator in EmulatorRegistry.forPlatform(platform),
          "${location.emulator} doesn't run $platform",
        )
      }
    }
  }

  @Test
  fun anEmulatorHasOneLocationPerPlatform() {
    for ((emulator, locations) in SaveLocations.all.groupBy { it.emulator }) {
      val scopes = locations.map { it.platforms }
      assertEquals(scopes.size, scopes.toSet().size, "$emulator has overlapping locations")
      val platforms = scopes.filterNotNull().flatten()
      assertEquals(platforms.size, platforms.toSet().size, "$emulator lists a platform twice")
    }
  }

  @Test
  fun platformLocationsBeatTheGeneralOne() {
    val retroArch = EmulatorId("retroarch")
    assertIs<SaveLayout.GameCubeGci>(
      SaveLocations.forEmulator(retroArch, PlatformCatalog.GAMECUBE)?.layout,
    )
    assertIs<SaveLayout.RomNamedFile>(
      SaveLocations.forEmulator(retroArch, PlatformCatalog.SNES)?.layout,
    )
    assertIs<SaveLayout.BesideRom>(
      SaveLocations.forEmulator(EmulatorId("pokedaisy"), PlatformCatalog.NDS)?.layout,
    )
    assertIs<SaveLayout.RomNamedFile>(
      SaveLocations.forEmulator(EmulatorId("pokedaisy"), PlatformCatalog.GBA)?.layout,
    )
    assertNull(SaveLocations.forEmulator(EmulatorId("flycast"), PlatformCatalog.ARCADE))
  }

  @Test
  fun biosKnowledgeIsWellFormed() {
    val ids = BiosKnowledge.locations.map { it.emulator }
    assertEquals(ids.size, ids.toSet().size)
    for (location in BiosKnowledge.locations) {
      assertKnownEmulator(location.emulator)
      assertKnownPlaceholders(location.emulator.value, location.paths)
    }
    for (md5 in BiosKnowledge.libretroNames.keys) assertTrue(Regex("[0-9a-f]{32}").matches(md5))
  }

  @Test
  fun biosRenames() {
    val hakuX = assertNotNull(BiosKnowledge.locationFor(EmulatorId("hakux")))
    assertEquals("flash.bin", hakuX.targetName("complex_4627.BIN"))
    assertEquals("other.bin", hakuX.targetName("other.bin"))
  }

  @Test
  fun biosLocationForAGame() {
    val retroArch =
      assertNotNull(BiosKnowledge.locationFor(EmulatorId("retroarch"), PlatformCatalog.PSX))
    assertEquals(
      "dc/dc_boot.bin",
      retroArch.targetName("boot.bin", "E10C53C2F8B90BAB96EAD2D368858623"),
    )
    assertEquals("boot.bin", retroArch.targetName("boot.bin", null))
    assertEquals(
      listOf("/sd/Android/data/dev.eden.fork/files"),
      assertNotNull(BiosKnowledge.locationFor(EmulatorId("eden"), PlatformCatalog.SWITCH))
        .folders("/sd", "dev.eden.fork"),
    )
    // GB needs no firmware, and DuckStation reads only PlayStation firmware.
    assertNull(BiosKnowledge.locationFor(EmulatorId("retroarch"), PlatformCatalog.GB))
    assertNull(BiosKnowledge.locationFor(EmulatorId("duckstation"), PlatformCatalog.PS2))
    assertNull(BiosKnowledge.locationFor(EmulatorId("ppsspp"), PlatformCatalog.PSP))
  }
}
