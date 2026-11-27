package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
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

class GameKeysTest {
  private val pocketGoal = SavedChoice("dev.sargunv.pocketgoal")
  private val armsx2 = SavedChoice("com.armsx2")
  private val local = GameKey.Local("/storage/emulated/0/ROMs/ps2/Jak.iso")
  private val rom = GameKey.Romm(42)

  private val dir = createTempDirectory("game-keys")
  private val path = dir.resolve("settings.json")

  @AfterTest
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  fun deleteDir() = dir.deleteRecursively()

  private fun settingsFile() =
    SettingsFile(Path(path.toString()), Settings.serializer(), Settings())

  private fun settings(vararg games: Pair<GameKey, SavedChoice>) =
    Settings(
      launch =
        LaunchSettings(
          games = games.toMap(),
          platforms = mapOf(PS2.value to armsx2),
        ),
    )

  @Test
  fun aLocalGamesChoiceBecomesItsRomsAndTheRomsOwnWins() {
    val adoption = Adoption(local, rom)
    assertEquals(
      settings(rom to pocketGoal),
      settings(local to pocketGoal).adopted(listOf(adoption)),
    )
    assertEquals(
      settings(rom to armsx2),
      settings(local to pocketGoal, rom to armsx2).adopted(listOf(adoption)),
    )
    val other = settings(GameKey.Romm(7) to armsx2)
    assertEquals(other, other.adopted(listOf(adoption)))
  }

  @Test
  fun choicesForGamesOfBothKindsSurviveARestart() = runTest {
    val stored = settings(local to pocketGoal, rom to armsx2)
    settingsFile().write(stored)
    assertEquals(stored, settingsFile().read())
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
}
