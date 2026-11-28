package dev.sargunv.handheld.data.settings

import dev.sargunv.handheld.data.waitFor
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class SettingsStoreTest {
  private val dir = createTempDirectory("settings-test").toFile()

  // A directory that doesn't exist yet, as on a fresh install.
  private val file = File(dir, "config/settings.json")

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  @Test
  fun keepsTheLatestChangeForTheNextLaunch() = runTest {
    val store = SettingsStore(file, backgroundScope)
    assertEquals(Settings(), store.settings.value)
    store.update { it.copy(closeEmulators = true) }
    store.update { it.copy(achievements = AchievementSettings(hardcore = true)) }
    val local = GameKey.Local("/storage/ROMs/ps2/Jak.iso")
    store.update { it.copy(launch = LaunchRules(mapOf(local to LaunchChoice.BuiltIn()))) }

    val latest = store.settings.value
    waitFor { while (SettingsStore(file, backgroundScope).settings.value != latest) delay(10) }
  }
}
