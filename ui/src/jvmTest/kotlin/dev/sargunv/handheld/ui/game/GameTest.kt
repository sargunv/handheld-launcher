package dev.sargunv.handheld.ui.game

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.TestServer
import dev.sargunv.handheld.ui.awaitTag
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalTestApi::class)
class GameTest {
  private val storage = createTempDirectory("game-storage").toFile()
  private val gameFile = File(storage, "ROMs/gb/Game 1.gb")

  @AfterTest fun cleanUp() = check(storage.deleteRecursively())

  /** A server with one game, Game 1 on the Game Boy, and one save of it from another device. */
  private val server = fakeRomm { path, _ ->
    json(
      when (path) {
        "platforms" -> "[${platform()}]"
        "roms" -> page(listOf(rom(1, lastPlayed = "2025-01-01")))
        "roms/identifiers" -> "[1]"
        "collections",
        "collections/smart" -> "[]"
        "saves" -> "[$Save]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  /** Launches with the game on the device, and opens its page from Home. */
  private fun ComposeUiTest.openGame(
    launcher: GameLauncher,
    apps: AppCatalog = AppCatalog.None,
    romHeaders: RomHeaders = RomHeaders.None,
  ) {
    launchHome(launcher, apps, romHeaders)
    press(Key.ButtonX)
    onNodeWithTag("menu:game").assertIsFocused()
    press(Key.ButtonA)
    awaitText("Play")
  }

  /** Launches with the game on the device, on Home. */
  private fun ComposeUiTest.launchHome(
    launcher: GameLauncher,
    apps: AppCatalog = AppCatalog.None,
    romHeaders: RomHeaders = RomHeaders.None,
  ) {
    gameFile.parentFile.mkdirs()
    gameFile.writeText("game")
    launchApp(
      server,
      credentials =
        InMemoryCredentialStore(RommCredentials(TestServer, "rmm_token", 1, "player", "thor")),
      storage = listOf(StorageTarget(storage.path, "Test storage")),
      launcher = launcher,
      apps = apps,
      romHeaders = romHeaders,
    )
    awaitTag("continue:1")
    // Home knows the game is on the device once its folder has been read.
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag("hint:Confirm") and hasText("Play")).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** Goes from Home to Settings → Emulators with the main menu, focusing its first setting. */
  private fun ComposeUiTest.openEmulatorsSettings() {
    press(Key.ButtonStart)
    awaitTag("main:Home")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("settings:Controls")
    // Controls, RomM, then Emulators: the test has no RetroAchievements.
    press(listOf(Key.DirectionDown, Key.DirectionDown))
    awaitTag("settings:emulators:gb")
    press(Key.DirectionRight)
  }

  @Test
  fun playsAGameOnTheDeviceAndSaysHowItWent() = runComposeUiTest {
    val launcher = FakeLauncher()
    openGame(launcher)
    onNodeWithTag("primary").assertIsFocused()

    press(Key.ButtonA)
    awaitText("Played")
    assertEquals(listOf(gameFile.path), launcher.played)
    onNodeWithTag("primary").assertIsFocused()
  }

  @Test
  fun confirmOnTheGameBeingPlayedPlaysIt() = runComposeUiTest {
    val launcher = FakeLauncher()
    launchHome(launcher)
    press(Key.ButtonA)
    // It plays on the game's page, which says how it went.
    awaitText("Played")
    assertEquals(listOf(gameFile.path), launcher.played)
    onNodeWithTag("primary").assertIsFocused()
    press(Key.ButtonB)
    awaitTag("continue:1")
  }

  @Test
  fun backWhileALaunchIsUnderWayAsksFirst() = runComposeUiTest {
    val hold = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(hold = hold)
    openGame(launcher)
    press(Key.ButtonA)
    awaitText("Downloading core 40%")
    // The menu waits for the launch, since what it opens would leave the screen.
    onNodeWithTag("hint:Menu").assertDoesNotExist()
    press(Key.ButtonB)
    // Staying has focus first, so one press too many keeps the launch going.
    onNodeWithTag("leave:cancel").assertIsFocused()
    press(Key.ButtonA)
    onNodeWithTag("leave:cancel").assertDoesNotExist()
    assertFalse(launcher.canceled)
    onNodeWithText("Downloading core 40%").assertExists()
    // Leaving stops the launch.
    press(Key.ButtonB)
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitTag("continue:1")
    assertTrue(launcher.canceled)
    assertTrue(launcher.played.isEmpty())
  }

  @Test
  fun aLaunchThatFinishesClosesTheQuestion() = runComposeUiTest {
    val hold = CompletableDeferred<Unit>()
    val launcher = FakeLauncher(hold = hold)
    openGame(launcher)
    press(Key.ButtonA)
    awaitText("Downloading core 40%")
    press(Key.ButtonB)
    onNodeWithTag("leave:cancel").assertIsFocused()
    hold.complete(Unit)
    awaitText("Played")
    onNodeWithTag("leave:cancel").assertDoesNotExist()
    onNodeWithTag("primary").assertIsFocused()
  }

  @Test
  fun asksWhichSaveToKeepBeforeAGameStarts() = runComposeUiTest {
    val now = Clock.System.now()
    val conflict =
      SaveConflict("Game 1", SaveVersion(now - 1.hours, 8192), SaveVersion(now - 2.hours, 8192))
    val launcher = FakeLauncher(conflict)
    openGame(launcher)

    press(Key.ButtonA)
    awaitText("Choose a save")
    // The newer copy, this device's, has focus.
    onNodeWithTag(SaveCopy.Device.name).assertIsFocused()
    press(Key.ButtonA)
    awaitText("Played")
    assertEquals(SaveCopy.Device, launcher.chosen)
    assertEquals(listOf(gameFile.path), launcher.played)
  }

  @Test
  fun backAtTheSaveChoiceCancelsTheLaunch() = runComposeUiTest {
    val now = Clock.System.now()
    val conflict =
      SaveConflict("Game 1", SaveVersion(now - 1.hours, 8192), SaveVersion(now - 2.hours, 8192))
    val launcher = FakeLauncher(conflict)
    openGame(launcher)

    press(Key.ButtonA)
    awaitText("Choose a save")
    press(Key.ButtonB)
    awaitTag("primary")
    onNodeWithTag("primary").assertIsFocused()
    assertTrue(launcher.played.isEmpty())
  }

  @Test
  fun asksBeforePullingWhileTheEmulatorMayStillBeRunning() = runComposeUiTest {
    val launcher = FakeLauncher(stillRunning = "ARMSX2")
    openGame(launcher)

    press(Key.ButtonA)
    awaitText("ARMSX2 may still be running")
    // Closing isn't offered when the setting is off.
    onNodeWithTag("stillRunning:quitFirst").assertIsFocused()
    onNodeWithTag("stillRunning:closeNow").assertDoesNotExist()
    press(Key.ButtonA)
    awaitTag("primary")
    assertEquals(StillRunningChoice.QuitFirst, launcher.stillRunningChoice)
    assertTrue(launcher.played.isEmpty(), "Quitting first cancels the launch")

    press(Key.ButtonA)
    awaitText("ARMSX2 may still be running")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Played")
    assertEquals(StillRunningChoice.PlayDeviceSave, launcher.stillRunningChoice)
    assertEquals(listOf(gameFile.path), launcher.played)

    // Back cancels, like quitting first.
    press(Key.ButtonA)
    awaitText("ARMSX2 may still be running")
    press(Key.ButtonB)
    awaitTag("primary")
    assertEquals(StillRunningChoice.QuitFirst, launcher.stillRunningChoice)
    assertEquals(listOf(false, false, false), launcher.closeEmulators)
  }

  @Test
  fun offersToCloseTheEmulatorWhenTheSettingIsOn() = runComposeUiTest {
    val launcher = FakeLauncher(stillRunning = "ARMSX2", canCloseEmulators = true)
    openGame(launcher)

    // The setting, in Settings → Emulators, is off by default, and turning it on explains it first.
    press(Key.ButtonB)
    awaitTag("continue:1")
    openEmulatorsSettings()
    onNodeWithTag("settings:closeEmulators").assertTextEquals("Close emulators", "Off")
    press(Key.ButtonA)
    awaitTag("closeEmulators:cancel")
    // Cancel has focus first, so one press too many leaves it off.
    onNodeWithTag("closeEmulators:cancel").assertIsFocused()
    press(listOf(Key.DirectionRight, Key.ButtonA))
    onNodeWithTag("settings:closeEmulators").assertTextEquals("Close emulators", "On")
    press(Key.ButtonB)

    // Confirm on the game being played plays it.
    awaitTag("continue:1")
    press(Key.ButtonA)
    awaitText("ARMSX2 may still be running")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitText("Played")
    assertEquals(StillRunningChoice.CloseNow, launcher.stillRunningChoice)
    assertEquals(listOf(true), launcher.closeEmulators)
  }

  @Test
  fun theCloseEmulatorsSettingShowsOnlyWhereTheDeviceCanClose() = runComposeUiTest {
    openGame(FakeLauncher())
    press(Key.ButtonB)
    awaitTag("continue:1")
    openEmulatorsSettings()
    onNodeWithTag("settings:closeEmulators").assertDoesNotExist()
  }

  @Test
  fun removesTheGameFromTheMenuAfterAsking() = runComposeUiTest {
    openGame(FakeLauncher())

    press(Key.ButtonX)
    onNodeWithTag("menu:remove").assertIsFocused()
    press(Key.ButtonA)
    // Cancel has focus first, so one press too many removes nothing.
    onNodeWithTag("remove:cancel").assertIsFocused()
    press(Key.ButtonA)
    assertTrue(gameFile.exists())

    press(listOf(Key.ButtonX, Key.ButtonA, Key.DirectionRight, Key.ButtonA))
    awaitText("Download (1.0 KB)")
    assertFalse(gameFile.exists())
    onNodeWithTag("primary").assertIsFocused()
  }

  @Test
  fun opensTheGameWithTheAppChosenForIt() = runComposeUiTest {
    val launcher = FakeLauncher()
    val apps = AppCatalog {
      listOf(InstalledApp("com.example.notes", "Notes"), InstalledApp("com.retroarch", "RetroArch"))
    }
    openGame(launcher, apps)

    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitText("Open Game 1 with")
    // The built-in session comes first, with the platform's core, and is the platform's default.
    // The known emulator follows, with the same core.
    onNodeWithTag("app:builtin:null").assertIsFocused()
    onNodeWithText("Built-in · Gambatte").assertExists()
    onNodeWithText("Game Boy default").assertExists()
    onNodeWithTag("app:com.retroarch:gambatte_libretro").assertExists()
    awaitText("Why it opens in Built-in · Gambatte")
    onNodeWithText("→ Platform Game Boy: Built-in (default) · opens here").assertExists()

    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitText("Play")
    press(Key.ButtonA)
    awaitText("Played")
    assertEquals(
      listOf(
        LaunchRules(gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice("com.example.notes"))),
      ),
      launcher.rules,
    )

    // Back on Open with, the game's choice is current, and clearing it goes back to the default.
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitText("Why it opens in Notes")
    onNodeWithText("→ Platform Game Boy: Built-in (default) · overridden").assertExists()
    onNodeWithTag("openWith:current").assertTextEquals("Notes")
    onNodeWithText("If cleared: Built-in · Gambatte").assertExists()
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Play")
    press(Key.ButtonA)
    waitUntil(timeoutMillis = 5_000) { launcher.rules.size == 2 }
    assertEquals(LaunchRules(), launcher.rules.last())
  }

  @Test
  fun theLowerScreenShowsTheSaveHistory() = runComposeUiTest {
    val kept = KeptSave("/kept", Clock.System.now() - 3.hours, SaveCopy.Device, "mgba", 2048)
    openGame(FakeLauncher(keptSaves = listOf(kept)))
    awaitText("Steam Deck")
    onNodeWithText("mgba · 1 day ago · 8 KB", substring = true).assertExists()
    onNodeWithText("This device's copy").assertExists()
    onNodeWithText("Opens in Test Emulator").assertExists()
    onNodeWithTag("game:header").assertDoesNotExist()
  }

  @Test
  fun theLowerScreenShowsWhatTheCopyOnTheDeviceSays() = runComposeUiTest {
    val read = mutableListOf<String>()
    val headers = RomHeaders { _, path ->
      read += path
      listOf(RomHeaderField("Title", "TIC-TAC-TOE"), RomHeaderField("Region", "Japan"))
    }
    openGame(FakeLauncher(), romHeaders = headers)
    awaitText("This copy")
    onNodeWithText("Title TIC-TAC-TOE · Region Japan").assertExists()
    assertEquals(listOf(gameFile.path), read.distinct())
  }

  private companion object {
    val Save: String
      get() {
        val yesterday = Clock.System.now() - 25.hours
        return """{"id": 5, "rom_id": 1, "file_name": "Game 1.sav", "file_size_bytes": 8192,
        "emulator": "mgba", "slot": "autosave", "download_path": "/api/saves/5/content",
        "origin_device_id": "deck", "device_syncs": [{"device_id": "deck",
        "device_name": "Steam Deck", "last_synced_at": "$yesterday"}],
        "created_at": "$yesterday", "updated_at": "$yesterday"}"""
      }
  }
}
