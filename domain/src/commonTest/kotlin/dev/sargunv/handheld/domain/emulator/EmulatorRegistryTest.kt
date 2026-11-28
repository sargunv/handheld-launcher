package dev.sargunv.handheld.domain.emulator

import dev.sargunv.handheld.domain.launch.ExtraValue
import dev.sargunv.handheld.domain.launch.LaunchMethod
import dev.sargunv.handheld.domain.launch.LaunchRecipe
import dev.sargunv.handheld.domain.launch.RomData
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
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
    assertEquals(
      EmulatorId("pocketgoal"),
      EmulatorRegistry.forPackage("dev.sargunv.pocketgoal")?.id,
    )
  }

  @Test
  fun patternsMatchWholePackageNames() {
    assertEquals(EmulatorId("armsx2"), EmulatorRegistry.forPackage("com.armsx2.nightly")?.id)
    assertNull(EmulatorRegistry.forPackage("com.armsx21"))
    assertNull(EmulatorRegistry.forPackage("xcom.armsx2.nightly"))
  }

  @Test
  fun fileUriRecipesGoThroughTheShell() {
    for ((name, recipe) in recipes) {
      if (recipe.data == RomData.FileUri) assertEquals(LaunchMethod.Shell, recipe.method, name)
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
    for (platform in EmulatorRegistry.preference.keys) {
      for (emulator in EmulatorRegistry.forPlatform(platform)) {
        for (recipe in listOfNotNull(emulator.recipe, emulator.forkRecipe)) {
          val command =
            recipe.commandFor(
              emulator.packageName,
              "/storage/emulated/0/ROMs/game.bin",
              platform,
              LibretroCores.recommendedFor(platform),
              null,
            )
          assertEquals(emulator.packageName, command?.packageName, "${emulator.id} on $platform")
        }
      }
    }
  }

  @Test
  fun retroArchPlatformsHaveCores() {
    for ((platform, ids) in EmulatorRegistry.preference) {
      if (EmulatorId("retroarch") !in ids) continue
      assertNotNull(LibretroCores.recommendedFor(platform), "no core for $platform")
    }
  }
}
