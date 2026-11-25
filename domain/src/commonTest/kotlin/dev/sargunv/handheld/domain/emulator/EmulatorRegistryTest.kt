package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.launch.ExtraValue
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchCommandBuilder
import dev.sargunv.handheld.domain.launch.LaunchFlag
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchRecipe
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorRegistryTest {
  private val emulators = EmulatorRegistry.all

  private val recipes: List<Pair<String, LaunchRecipe>> = emulators.flatMap { emulator ->
    listOfNotNull(
      emulator.id.value to emulator.recipe,
      emulator.forkRecipe?.let { "${emulator.id} fork" to it },
    )
  }

  @Test
  fun idsAreUnique() {
    val ids = emulators.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun packagesAreUnique() {
    val packages = emulators.map { it.packageName }
    assertEquals(packages.size, packages.toSet().size)
  }

  @Test
  fun everyPackageFindsItsOwnEmulator() {
    for (emulator in emulators) {
      assertEquals(emulator, EmulatorRegistry.forPackage(emulator.packageName), emulator.id.value)
    }
  }

  @Test
  fun platformsAreCatalogPlatformsThatRunAsThemselves() {
    for (emulator in emulators) {
      assertTrue(emulator.platforms.isNotEmpty(), "${emulator.id} runs no platform")
      for (slug in emulator.platforms) {
        val platform = assertNotNull(PlatformCatalog[slug], "${emulator.id}: $slug")
        assertNull(platform.runsAs, "${emulator.id} lists the twin $slug")
      }
    }
  }

  @Test
  fun everyPlatformIsCovered() {
    for (platform in PlatformCatalog.all) {
      assertTrue(
        EmulatorRegistry.forPlatform(platform.slug).isNotEmpty(),
        "no emulator runs ${platform.slug}",
      )
    }
  }

  @Test
  fun preferenceListsEveryEmulatorOfAPlatformOnce() {
    for (platform in PlatformCatalog.all.filter { it.runsAs == null }) {
      val ranked = EmulatorRegistry.preference[platform.slug].orEmpty()
      assertEquals(ranked.size, ranked.toSet().size, "${platform.slug} ranks an emulator twice")
      val running = emulators.filter { platform.slug in it.platforms }.map { it.id }.toSet()
      assertEquals(running, ranked.toSet(), "${platform.slug} ranking")
    }
    for (slug in EmulatorRegistry.preference.keys) assertNotNull(PlatformCatalog[slug])
  }

  @Test
  fun twinsUseTheirPlatformsEmulators() {
    assertEquals(
      EmulatorRegistry.forPlatform(PlatformCatalog.NES),
      EmulatorRegistry.forPlatform(PlatformCatalog.FAMICOM),
    )
  }

  @Test
  fun forkPatternsFindTheirEmulator() {
    assertEquals(
      EmulatorId("eden"),
      EmulatorRegistry.forPackage("dev.eden.eden_emulator.nightly")?.id,
    )
    assertEquals(EmulatorId("dolphin"), EmulatorRegistry.forPackage("org.dolphinemu.mmjr")?.id)
    assertEquals(EmulatorId("azahar"), EmulatorRegistry.forPackage("io.github.lime3ds.android")?.id)
    assertNull(EmulatorRegistry.forPackage("dev.sargunv.pocketgoal"))
  }

  @Test
  fun patternsMatchWholePackageNames() {
    val armsx2 = assertNotNull(EmulatorRegistry[EmulatorId("armsx2")])
    assertTrue(armsx2.matches("com.armsx2"))
    assertTrue(armsx2.matches("com.armsx2.nightly"))
    assertTrue(!armsx2.matches("com.armsx21"))
    assertTrue(!armsx2.matches("xcom.armsx2.nightly"))
  }

  @Test
  fun recipesLeaveGrantsToTheBuilder() {
    for ((name, recipe) in recipes) {
      assertTrue(LaunchFlag.GrantReadUriPermission !in recipe.flags, name)
    }
  }

  @Test
  fun recipeTextUsesKnownPlaceholders() {
    val placeholder = Regex("""\{(\w+)}""")
    for ((name, recipe) in recipes) {
      for (value in recipe.extras.values.filterIsInstance<ExtraValue.Text>()) {
        for (match in placeholder.findAll(value.template)) {
          assertTrue(match.groupValues[1] in LaunchCommandBuilder.placeholders, "$name: $value")
        }
      }
    }
  }

  @Test
  fun recipesHandOverTheGame() {
    val gameExtras =
      setOf(
        ExtraValue.RomPath::class,
        ExtraValue.RomUri::class,
        ExtraValue.RomUriString::class,
        ExtraValue.RomPathSinceBuild::class,
        ExtraValue.VitaTitleArgs::class,
      )
    for ((name, recipe) in recipes) {
      val passesRom = recipe.data != null || recipe.extras.values.any { it::class in gameExtras }
      assertTrue(passesRom, "$name never receives the game")
    }
  }

  @Test
  fun everyRecipeBuildsForEveryPlatformItRuns() {
    for (emulator in emulators) {
      for (platform in emulator.platforms) {
        val input = LaunchInput(romPath = "/storage/emulated/0/ROMs/game.bin", platform = platform)
        val build = LaunchCommandBuilder.build(emulator.recipe, emulator.packageName, input)
        val command = assertIs<LaunchBuild.Ready>(build, "${emulator.id} on $platform").command
        assertEquals(emulator.packageName, command.packageName)
      }
    }
  }

  @Test
  fun retroArchPlatformsHaveCores() {
    val retroArch = assertNotNull(EmulatorRegistry[EmulatorId("retroarch")])
    for (platform in retroArch.platforms) {
      assertNotNull(LibretroCores.recommendedFor(platform), "no core for $platform")
    }
  }
}
