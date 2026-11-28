package dev.sargunv.handheld.ui.game

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.lastPlayed
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder

/**
 * What the app keeps for a local game goes to the server's game once the file is that game's: the
 * device has Homebrew.gb, which the server gains as Homebrew (World).
 */
@OptIn(ExperimentalTestApi::class)
class AdoptionTest {
  private val temp = TemporaryFolder().apply { create() }

  private val storage = temp.newFolder()
  private val homebrew = File(storage, "ROMs/gb/Homebrew.gb")
  private val library = LibraryCache.open(path = null)
  private val local = GameKey.Local(homebrew.path)
  private val rom = GameKey.Romm(2)
  private val choice = LaunchChoice.App("com.example.emulator")

  @Volatile private var serverHasHomebrew = false

  private lateinit var data: HandheldData

  /** The game choices the app keeps. */
  private val overrides: Map<GameKey, LaunchChoice>
    get() = data.settings.settings.value.launch.gameOverrides

  init {
    homebrew.parentFile.mkdirs()
    homebrew.writeText("homebrew")
  }

  @AfterTest
  fun cleanUp() {
    library.close()
    temp.delete()
  }

  private val server = fakeRomm { path, request ->
    json(
      when (path) {
        "platforms" -> "[${platform()}]"
        "roms" ->
          if (request.lastPlayed) page(emptyList())
          else page(listOfNotNull(HomebrewRom.takeIf { serverHasHomebrew }))
        "roms/identifiers" -> if (serverHasHomebrew) "[2]" else "[]"
        "collections",
        "collections/smart" -> "[]"
        "saves" -> "[]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  /** Opens Homebrew's page, with an Open with choice for its file. */
  private fun ComposeUiTest.openHomebrew(launcher: GameLauncher = FakeLauncher()) {
    data =
      launchApp(
        temp,
        server,
        library = library,
        storage = listOf(StorageTarget(storage.path, "Test storage")),
        settings = Settings(launch = LaunchRules(gameOverrides = mapOf(local to choice))),
        launcher = launcher,
        open = OpenTarget.Local(homebrew.path),
      )
    awaitText("Play")
  }

  private fun ComposeUiTest.serverGainsHomebrew() {
    serverHasHomebrew = true
    data.romm.syncNow()
    waitUntil(timeoutMillis = 5_000) {
      runBlocking { data.downloads.statuses().first() }[rom.romId] is RomStatus.OnDevice
    }
  }

  @Test
  fun aLocalGamesStateGoesToTheServersGameInOneStep() = runComposeUiTest {
    openHomebrew()
    serverGainsHomebrew()
    awaitText("Homebrew (World)")
    waitUntil(timeoutMillis = 5_000) { overrides == mapOf(rom to choice) }
  }

  @Test
  fun aGameThatsPlayingKeepsItsStateWhereItIsUntilItsOver() = runComposeUiTest {
    val hold = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(hold = hold)
    openHomebrew(launcher)
    press(Key.ButtonA)
    runBlocking { launcher.started.await() }
    serverGainsHomebrew()
    waitForIdle()
    assertEquals(mapOf<GameKey, LaunchChoice>(local to choice), overrides)

    hold.complete(Unit)
    waitUntil(timeoutMillis = 5_000) { overrides == mapOf(rom to choice) }
  }

  @Test
  fun aGameWhoseSaveIsStillSyncingKeepsItsStateAfterItsScreenGoesAway() = runComposeUiTest {
    val afterGame = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(afterGame = afterGame)
    openHomebrew(launcher)
    press(Key.ButtonA)
    waitUntil(timeoutMillis = 5_000) {
      launcher.playing.value == null && local in launcher.plays.value
    }
    press(Key.ButtonB)
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithTag("primary").fetchSemanticsNodes().isEmpty()
    }
    serverGainsHomebrew()
    waitForIdle()
    assertEquals(mapOf<GameKey, LaunchChoice>(local to choice), overrides)

    afterGame.complete(Unit)
    waitUntil(timeoutMillis = 5_000) { overrides == mapOf(rom to choice) }
  }

  private companion object {
    val HomebrewRom =
      rom(2, "Homebrew (World)", file = "Homebrew.gb", size = 8, added = "2024-01-02")
  }
}
