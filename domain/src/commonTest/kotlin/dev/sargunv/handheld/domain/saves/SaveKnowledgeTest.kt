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
        assertTrue(platform in emulator.platforms, "${location.emulator} doesn't run $platform")
      }
    }
  }

  @Test
  fun everyEmulatorHasSaveKnowledgeOrIsListedAsUnmapped() {
    for (emulator in EmulatorRegistry.all) {
      val mapped = SaveLocations.all.any { it.emulator == emulator.id }
      assertTrue(mapped != emulator.id in SaveLocations.unmapped, emulator.id.value)
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
  fun consoleLayouts() {
    fun layout(id: String, platform: dev.sargunv.handheld.domain.platform.PlatformSlug) =
      SaveLocations.forEmulator(EmulatorId(id), platform)?.layout
    assertEquals(SaveLayout.Ps2MemoryCards, layout("armsx2", PlatformCatalog.PS2))
    assertEquals(SaveLayout.GameCubeGci, layout("dolphin", PlatformCatalog.GAMECUBE))
    assertEquals(SaveLayout.WiiTitle, layout("dolphin", PlatformCatalog.WII))
    assertEquals(SaveLayout.SwitchUserSave, layout("eden", PlatformCatalog.SWITCH))
    assertEquals(SaveLayout.DreamcastVmu, layout("flycast", PlatformCatalog.DREAMCAST))
    assertEquals(SaveLayout.SerialPrefixedFolders, layout("ppsspp", PlatformCatalog.PSP))
    assertEquals(SaveLayout.ThreeDsTitle, layout("azahar", PlatformCatalog.NEW_N3DS))
  }

  @Test
  fun stateLocationsAreWellFormed() {
    val ids = StateLocations.all.map { it.emulator }
    assertEquals(ids.size, ids.toSet().size)
    for (location in StateLocations.all) {
      assertKnownEmulator(location.emulator)
      assertKnownPlaceholders(location.emulator.value, location.paths)
      assertTrue(location.paths.isNotEmpty())
      location.maxSlots?.let { assertTrue(it > 0) }
    }
    assertEquals(
      StateNaming.DuckStationSerial,
      StateLocations.forEmulator(EmulatorId("duckstation"))?.naming,
    )
  }

  @Test
  fun biosKnowledgeIsWellFormed() {
    val ids = BiosKnowledge.locations.map { it.emulator }
    assertEquals(ids.size, ids.toSet().size)
    for (location in BiosKnowledge.locations) {
      assertKnownEmulator(location.emulator)
      assertKnownPlaceholders(location.emulator.value, location.paths)
      for (platform in location.platforms.orEmpty()) assertNotNull(PlatformCatalog[platform])
    }
    for ((platform, files) in BiosKnowledge.files) {
      assertNull(PlatformCatalog[platform]?.runsAs, "$platform is a twin")
      for (file in files) file.md5?.let { assertTrue(Regex("[0-9a-f]{32}").matches(it), it) }
    }
    for (md5 in BiosKnowledge.libretroNames.keys) assertTrue(Regex("[0-9a-f]{32}").matches(md5))
  }

  @Test
  fun biosRenames() {
    val hakuX = assertNotNull(BiosKnowledge.locationFor(EmulatorId("hakux")))
    assertEquals("flash.bin", hakuX.targetName("complex_4627.BIN"))
    assertEquals("other.bin", hakuX.targetName("other.bin"))
    assertEquals("scph5501.bin", BiosKnowledge.libretroNames["490f666e1afb15b7362b406ed1cea246"])
    assertEquals(BiosKnowledge.filesFor(PlatformCatalog.PSX).size, 4)
  }
}
