package dev.sargunv.handheld.data.settings

import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

class SettingsFileTest {
  @Serializable private data class TestSettings(val hand: Hand = Hand.Right, val volume: Int = 5)

  private enum class Hand {
    Left,
    Right,
  }

  private val dir = createTempDirectory("settings-test")
  // A directory that doesn't exist yet, as on a fresh install.
  private val file = dir.resolve("config/settings.json")

  private fun settingsFile() =
    SettingsFile(Path(file.toString()), TestSettings.serializer(), TestSettings())

  @AfterTest
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  fun deleteDir() = dir.deleteRecursively()

  @Test
  fun readsTheDefaultsWithoutAFile() {
    assertEquals(TestSettings(), settingsFile().read())
  }

  @Test
  fun keepsWrittenSettingsForTheNextLaunch() = runTest {
    val settings = TestSettings(Hand.Left, 7)
    settingsFile().write(settings)
    assertEquals(settings, settingsFile().read())
  }

  @Test
  fun aFailedWriteDoesntThrow() = runTest {
    // A file where the settings directory should be makes every write fail.
    file.parent.parent.toFile().mkdirs()
    file.parent.toFile().writeText("")
    settingsFile().write(TestSettings(volume = 1))
  }
}
