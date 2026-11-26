package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.CollectionRule
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import dev.sargunv.handheld.ui.Settings
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class LaunchSettingsTest {
  private val pocketGoal = SavedChoice("dev.sargunv.pocketgoal")
  private val armsx2 = SavedChoice("com.armsx2")
  private val jak = Collection(CollectionKind.Regular, 7, "Jak and Daxter", "", 3, false)

  private val dir = createTempDirectory("launch-settings")

  @AfterTest
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  fun deleteDir() = dir.deleteRecursively()

  private fun settingsFile() =
    SettingsFile(Path(dir.resolve("settings.json").toString()), Settings.serializer(), Settings())

  @Test
  fun theChoicesAtEachLevelSurviveARestart() = runTest {
    val launch =
      LaunchSettings()
        .withChoice(RuleLevel.Game(1), armsx2)
        .withChoice(RuleLevel.InCollection(jak.launchId, PS2), pocketGoal)
        .withChoice(RuleLevel.Platform(GBA), SavedChoice("com.retroarch", "mgba_libretro"))
    settingsFile().write(Settings(launch = launch))

    val read = settingsFile().read().launch
    assertEquals(launch, read)
    assertEquals(
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice("com.armsx2")),
        collectionRules =
          listOf(
            CollectionRule(CollectionId("regular:7"), LaunchChoice("dev.sargunv.pocketgoal"), PS2),
          ),
        platformDefaults = mapOf(GBA to LaunchChoice("com.retroarch", core = "mgba_libretro")),
      ),
      read.toRules(),
    )
  }

  @Test
  fun settingsStoredBeforeLaunchRulesHaveNone() {
    dir.resolve("settings.json").writeText("""{"pauseGameWhenUnfocused": true}""")
    assertEquals(LaunchSettings(), settingsFile().read().launch)
  }

  @Test
  fun aNewChoiceReplacesTheLevelsChoiceAndClearingRemovesIt() {
    val level = RuleLevel.InCollection(jak.launchId, PS2)
    val replaced = LaunchSettings().withChoice(level, armsx2).withChoice(level, pocketGoal)
    assertEquals(pocketGoal, replaced.choiceAt(level))
    assertEquals(1, replaced.collections.size)
    // The same collection's games on another platform keep their own choice.
    val other = RuleLevel.InCollection(jak.launchId, GBA)
    assertEquals(null, replaced.choiceAt(other))

    assertEquals(LaunchSettings(), replaced.withChoice(level, null))
    assertEquals(
      LaunchSettings(),
      LaunchSettings().withChoice(RuleLevel.Game(1), armsx2).withChoice(RuleLevel.Game(1), null),
    )
  }
}
