package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalTestApi::class)
class GameTest {
  private val storage = createTempDirectory("game-storage").toFile()
  private val gameFile = File(storage, "ROMs/gb/Game 1.gb")

  @AfterTest fun cleanUp() = check(storage.deleteRecursively())

  /** Plays nothing, but records what it was asked, and asks [conflict] first when given. */
  private class FakeLauncher(val conflict: SaveConflict? = null) : GameLauncher {
    val played = mutableListOf<String>()
    val rules = mutableListOf<LaunchRules>()
    var chosen: SaveCopy? = null

    override suspend fun opensIn(rom: Rom, rules: LaunchRules, collections: Set<CollectionId>) =
      "Opens in Test Emulator"

    override suspend fun play(
      rom: Rom,
      path: String,
      rules: LaunchRules,
      collections: Set<CollectionId>,
      onProgress: (String) -> Unit,
      chooseSave: suspend (SaveConflict) -> SaveCopy?,
    ): List<String> {
      if (conflict != null) {
        chosen = chooseSave(conflict) ?: return emptyList()
      }
      played += path
      this.rules += rules
      return listOf("Couldn't sync the save with RomM: Forbidden.")
    }

    override suspend fun keptSaves(rom: Rom) =
      listOf(KeptSave("/kept", Clock.System.now() - 3.hours, SaveCopy.Device, "mgba", 2048))
  }

  /** A server with one game, Game 1 on the Game Boy, and one save of it from another device. */
  private val server = {
    MockEngine { request ->
      val body =
        when (request.url.encodedPath.substringAfter("/api/")) {
          "platforms" -> "[$Platform]"
          "roms" ->
            if (request.url.parameters["last_played"] == "true") page(listOf(Rom1))
            else page(listOf(Rom1))
          "roms/identifiers" -> "[1]"
          "collections",
          "collections/smart" -> "[]"
          "saves" -> "[$Save]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  /** Launches with the game on the device, and opens it from Home. */
  private fun ComposeUiTest.openGame(
    launcher: GameLauncher,
    apps: AppCatalog = AppCatalog.None,
  ) {
    gameFile.parentFile.mkdirs()
    gameFile.writeText("game")
    setContent {
      val heldKeys = remember { HeldKeys() }
      val credentials = remember {
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token", "thor"))
      }
      val library = remember { LibraryCache.open(path = null) }
      App(
        DualScreenLayout.Frame,
        heldKeys,
        credentials,
        TestDevice,
        library,
        rememberTestDownloads(
          library,
          credentials,
          listOf(StorageTarget(storage.path, "Test storage")),
        ),
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        httpEngine = server,
        launcher = launcher,
        apps = apps,
      )
    }
    awaitTag("continue:1")
    press(Key.ButtonA)
    awaitText("Play")
  }

  private fun ComposeUiTest.awaitTag(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.awaitText(text: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      onRoot().performKeyInput { keyDown(key) }
      waitForIdle()
      onRoot().performKeyInput { keyUp(key) }
      waitForIdle()
    }
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  @Test
  fun playsAGameOnTheDeviceAndSaysHowItWent() = runComposeUiTest {
    val launcher = FakeLauncher()
    openGame(launcher)
    onNodeWithTag("primary").assertIsFocused()

    press(Key.ButtonA)
    awaitText("Couldn't sync the save with RomM: Forbidden.")
    assertEquals(listOf(gameFile.path), launcher.played)
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
    awaitText("Which save do you want to play?")
    // The newer copy, this device's, has focus.
    onNodeWithTag(SaveCopy.Device.name).assertIsFocused()
    press(Key.ButtonA)
    awaitText("Couldn't sync the save")
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
    awaitText("Which save do you want to play?")
    press(Key.ButtonB)
    awaitTag("primary")
    onNodeWithTag("primary").assertIsFocused()
    assertTrue(launcher.played.isEmpty())
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
    // The known emulator comes first, with the platform's core, and is the platform's default.
    onNodeWithTag("app:com.retroarch:gambatte_libretro").assertIsFocused()
    onNodeWithText("Game Boy default").assertExists()
    awaitText("Why it opens in RetroArch")
    onNodeWithText("→ Platform Game Boy: RetroArch (default) · opens here").assertExists()

    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Play")
    press(Key.ButtonA)
    awaitText("Couldn't sync the save")
    assertEquals(
      listOf(LaunchRules(gameOverrides = mapOf(1L to LaunchChoice("com.example.notes")))),
      launcher.rules,
    )

    // Back on Open with, the game's choice is current, and clearing it goes back to the default.
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitText("Why it opens in Notes")
    onNodeWithText("→ Platform Game Boy: RetroArch (default) · overridden").assertExists()
    press(listOf(Key.DirectionRight, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitText("Play")
    press(Key.ButtonA)
    waitUntil(timeoutMillis = 5_000) { launcher.rules.size == 2 }
    assertEquals(LaunchRules(), launcher.rules.last())
  }

  @Test
  fun theLowerScreenShowsTheSaveHistory() = runComposeUiTest {
    openGame(FakeLauncher())
    awaitText("Steam Deck")
    onNodeWithText("mgba · 1 day ago · 8 KB", substring = true).assertExists()
    onNodeWithText("This device's copy").assertExists()
    onNodeWithText("Opens in Test Emulator").assertExists()
  }

  private companion object {
    const val Platform =
      """{"id": 1, "slug": "gb", "fs_slug": "gb", "name": "Game Boy", "display_name": "Game Boy",
      "rom_count": 1, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    const val Rom1 =
      """{"id": 1, "platform_id": 1, "platform_slug": "gb", "platform_fs_slug": "gb",
      "platform_display_name": "Game Boy", "name": "Game 1", "fs_name": "Game 1.gb",
      "fs_name_no_tags": "Game 1", "fs_name_no_ext": "Game 1", "fs_extension": "gb",
      "fs_size_bytes": 1024, "path_cover_small": "", "url_cover": "",
      "created_at": "2024-01-01T00:00:00+00:00", "updated_at": "2024-01-01T00:00:00+00:00",
      "rom_user": {"last_played": "2025-01-01T00:00:00+00:00"}}"""

    val Save: String
      get() {
        val yesterday = Clock.System.now() - 25.hours
        return """{"id": 5, "rom_id": 1, "file_name": "Game 1.sav", "file_size_bytes": 8192,
        "emulator": "mgba", "slot": "autosave", "download_path": "/api/saves/5/content",
        "origin_device_id": "deck", "device_syncs": [{"device_id": "deck",
        "device_name": "Steam Deck", "last_synced_at": "$yesterday"}],
        "created_at": "$yesterday", "updated_at": "$yesterday"}"""
      }

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
