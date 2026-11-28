package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchRulesTest {
  private val pocketGoal = LaunchChoice.App("dev.sargunv.pocketgoal")
  private val armsx2 = LaunchChoice.App("com.armsx2")
  private val jak = Collection(CollectionKind.Regular, 7, "Jak and Daxter", "", 3, false)

  private val dir = createTempDirectory("launch-rules").toFile()

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  @Test
  fun theChoicesAtEachLevelSurviveARestart() {
    val rules =
      LaunchRules()
        .withChoice(OpenWithScope.Game(GameKey.Romm(42)), armsx2)
        .withChoice(OpenWithScope.Game(GameKey.Local("/storage/ROMs/ps2/Jak.iso")), pocketGoal)
        .withChoice(OpenWithScope.Collection(jak.launchId, PS2), pocketGoal)
        .withChoice(OpenWithScope.Platform(GBA), LaunchChoice.App("com.retroarch", "mgba_libretro"))
    val file = JsonFile(dir.resolve("settings.json"), Settings.serializer())
    file.write(Settings(launch = rules))
    assertEquals(rules, file.read()?.launch)
  }

  @Test
  fun aNewChoiceReplacesTheLevelsChoiceAndClearingRemovesIt() {
    val scope = OpenWithScope.Collection(jak.launchId, PS2)
    val replaced = LaunchRules().withChoice(scope, armsx2).withChoice(scope, pocketGoal)
    assertEquals(pocketGoal, replaced.choiceAt(scope))
    assertEquals(1, replaced.collectionRules.size)
    // The same collection's games on another platform keep their own choice.
    assertNull(replaced.choiceAt(OpenWithScope.Collection(jak.launchId, GBA)))

    assertEquals(LaunchRules(), replaced.withChoice(scope, null))
    val game = OpenWithScope.Game(GameKey.Romm(1))
    assertEquals(LaunchRules(), LaunchRules().withChoice(game, armsx2).withChoice(game, null))
  }
}
