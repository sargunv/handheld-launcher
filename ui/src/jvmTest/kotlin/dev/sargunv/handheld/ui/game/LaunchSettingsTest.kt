package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.GameKey
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
  private val local = GameKey.Local("/storage/emulated/0/ROMs/ps2/Jak.iso")
  private val rom = GameKey.Romm(42)

  private val dir = createTempDirectory("launch-settings")
  private val path = dir.resolve("settings.json")

  @AfterTest
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  fun deleteDir() = dir.deleteRecursively()

  private fun settingsFile() =
    SettingsFile(Path(path.toString()), Settings.serializer(), Settings())

  @Test
  fun theChoicesAtEachLevelSurviveARestart() = runTest {
    val launch =
      LaunchSettings()
        .withChoice(OpenWithScope.Game(rom), armsx2)
        .withChoice(OpenWithScope.Game(local), pocketGoal)
        .withChoice(OpenWithScope.Collection(jak.launchId, PS2), pocketGoal)
        .withChoice(OpenWithScope.Platform(GBA), SavedChoice("com.retroarch", "mgba_libretro"))
    settingsFile().write(Settings(launch = launch))

    val read = settingsFile().read().launch
    assertEquals(launch, read)
    assertEquals(
      LaunchRules(
        gameOverrides =
          mapOf(
            rom to LaunchChoice("com.armsx2"),
            local to LaunchChoice("dev.sargunv.pocketgoal"),
          ),
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
  fun readsGamesKeyedByRommIdAndLocalPath() {
    path.writeText(
      """
      {"launch": {"games": {
        "romm:42": {"packageName": "com.armsx2"},
        "local:/storage/emulated/0/ROMs/ps2/Jak.iso": {"packageName": "dev.sargunv.pocketgoal"}
      }}}
      """,
    )
    assertEquals(
      LaunchSettings(games = mapOf(rom to armsx2, local to pocketGoal)),
      settingsFile().read().launch,
    )
  }

  @Test
  fun aLocalGamesChoiceBecomesItsRomsAndTheRomsOwnWins() {
    fun settings(vararg games: Pair<GameKey, SavedChoice>) =
      Settings(launch = LaunchSettings(games = games.toMap()))
    val adoptions = listOf(Adoption(local, rom))
    assertEquals(settings(rom to pocketGoal), settings(local to pocketGoal).adopted(adoptions))
    assertEquals(
      settings(rom to armsx2),
      settings(local to pocketGoal, rom to armsx2).adopted(adoptions),
    )
    val other = settings(GameKey.Romm(7) to armsx2)
    assertEquals(other, other.adopted(adoptions))
  }

  @Test
  fun aNewChoiceReplacesTheLevelsChoiceAndClearingRemovesIt() {
    val scope = OpenWithScope.Collection(jak.launchId, PS2)
    val replaced = LaunchSettings().withChoice(scope, armsx2).withChoice(scope, pocketGoal)
    assertEquals(pocketGoal, replaced.choiceAt(scope))
    assertEquals(1, replaced.collections.size)
    // The same collection's games on another platform keep their own choice.
    val other = OpenWithScope.Collection(jak.launchId, GBA)
    assertEquals(null, replaced.choiceAt(other))

    assertEquals(LaunchSettings(), replaced.withChoice(scope, null))
    assertEquals(
      LaunchSettings(),
      LaunchSettings()
        .withChoice(OpenWithScope.Game(GameKey.Romm(1)), armsx2)
        .withChoice(OpenWithScope.Game(GameKey.Romm(1)), null),
    )
  }
}
