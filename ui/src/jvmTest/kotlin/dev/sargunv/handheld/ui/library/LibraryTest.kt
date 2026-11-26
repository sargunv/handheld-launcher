package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalTestApi::class)
class LibraryTest {
  /**
   * A server with two platforms of games and an empty third. On the Game Boy: Alpha, Bravo and
   * Zulu, with Zulu added first and Bravo last. On the NES: Contra and Metroid.
   */
  private val server = {
    MockEngine { request ->
      val body =
        when (request.url.encodedPath.substringAfter("/api/")) {
          "platforms" ->
            "[${platform(1, "Game Boy", 3)}, ${platform(2, "NES", 2)}, ${platform(3, "N64", 0)}]"
          "roms" ->
            if (request.url.parameters["last_played"] == "true") page(emptyList())
            else {
              page(
                listOf(
                  rom(1, 1, "Alpha", day = 2),
                  rom(2, 1, "Bravo", day = 3),
                  rom(3, 1, "Zulu", day = 1),
                  rom(4, 2, "Contra", day = 4),
                  rom(5, 2, "Metroid", day = 5),
                ),
              )
            }
          "roms/identifiers" -> "[1, 2, 3, 4, 5]"
          "collections",
          "collections/smart" -> "[]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  private fun ComposeUiTest.launch(
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
    deviceStates: Map<Long, DeviceState> = emptyMap(),
  ) {
    setContent {
      val heldKeys = remember { HeldKeys() }
      App(
        DualScreenLayout.Frame,
        heldKeys,
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token")),
        TestDevice,
        remember { LibraryCache.open(path = null) },
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        initialSettings = settings,
        saveSettings = saveSettings,
        httpEngine = server,
        deviceStates = flowOf(deviceStates),
      )
    }
  }

  /** Launches and goes to Library once the sync has filled it. */
  private fun ComposeUiTest.openLibrary(
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
    deviceStates: Map<Long, DeviceState> = emptyMap(),
  ) {
    launch(settings, saveSettings, deviceStates)
    awaitTag("shelf:RecentlyAdded")
    press(Key.ButtonR1)
  }

  private fun ComposeUiTest.awaitTag(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      // The key goes up once the screen settles: the test tracks held keys through the focused
      // element, and a screen change can leave nothing focused for a moment.
      onRoot().performKeyInput { keyDown(key) }
      waitForIdle()
      onRoot().performKeyInput { keyUp(key) }
      waitForIdle()
    }
  }

  private fun ComposeUiTest.assertFocused(tag: String) {
    awaitTag(tag)
    onNodeWithTag(tag).assertIsFocused()
  }

  @Test
  fun shoulderButtonsStepThroughPlatformsAndOnToTheOtherPlaces() = runComposeUiTest {
    openLibrary()
    awaitTag("shelf:platform:1")
    assertFocused("game:1")
    press(Key.DirectionRight)
    assertFocused("game:2")
    press(Key.ButtonR1)
    // The empty platform doesn't show, so the NES is the last.
    awaitTag("shelf:platform:2")
    assertFocused("game:4")
    press(Key.ButtonR1)
    onNodeWithText("Downloads are coming soon.").assertExists()
    press(Key.ButtonL1)
    assertFocused("game:4")
    // Each platform keeps its own focus.
    press(Key.ButtonL1)
    assertFocused("game:2")
    press(Key.ButtonL1)
    awaitTag("shelf:RecentlyAdded")
  }

  @Test
  fun aGameOpensItsScreenAndBackComesToIt() = runComposeUiTest {
    openLibrary()
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    onNodeWithText("The game screen is coming soon.").assertExists()
    press(Key.ButtonB)
    assertFocused("game:2")
  }

  @Test
  fun theMenuSortsAndJumpsToALetter() = runComposeUiTest {
    openLibrary()
    assertFocused("game:1")
    // The first choice sorts by recently added, which starts again at the newest game.
    press(Key.ButtonX)
    assertFocused("menu:sort:RecentlyAdded")
    press(Key.ButtonA)
    assertFocused("game:2")
    // Sorting by name again comes back to the game that had focus then.
    press(listOf(Key.ButtonX, Key.ButtonA))
    assertFocused("game:1")
    // Past the sort, the filters and "All games" come the letters: A, B and Z.
    press(Key.ButtonX)
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
    press(listOf(Key.DirectionLeft, Key.DirectionLeft))
    assertFocused("menu:jump:A")
    press(listOf(Key.DirectionRight, Key.DirectionRight, Key.ButtonA))
    assertFocused("game:3")
  }

  @Test
  fun allGamesTurnsOnFromTheMenuAndStays() = runComposeUiTest {
    val saved = mutableListOf<Settings>()
    openLibrary(saveSettings = { saved += it })
    assertFocused("game:1")
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
    assertFocused("menu:allGames")
    press(Key.ButtonA)
    awaitTag("shelf:all")
    assertEquals(true, saved.last().library.allGames)
    // The whole library, in title order.
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.DirectionRight))
    assertFocused("game:4")
    // "All games" is the first shelf.
    press(Key.ButtonR1)
    awaitTag("shelf:platform:1")
    press(listOf(Key.ButtonL1, Key.ButtonL1))
    awaitTag("shelf:RecentlyAdded")
  }

  @Test
  fun allGamesIsTheFirstShelfWhenOn() = runComposeUiTest {
    val saved = mutableListOf<Settings>()
    openLibrary(Settings(library = LibrarySettings(allGames = true)), { saved += it })
    awaitTag("shelf:all")
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("shelf:platform:1")
    assertEquals(LibrarySettings(), saved.last().library)
  }

  @Test
  fun coversShowWhichGamesAreOnTheDeviceAndTheLowerScreenFilters() = runComposeUiTest {
    openLibrary(deviceStates = mapOf(3L to DeviceState.OnDevice))
    assertFocused("game:1")
    onNodeWithTag("onDevice:3").assertExists()
    onNodeWithTag("onDevice:1").assertDoesNotExist()
    onNodeWithText("3 games, 1 on this device").assertExists()
    onNodeWithTag("touch:filter:OnDevice").performClick()
    assertFocused("game:3")
    onNodeWithTag("game:1").assertDoesNotExist()
    // The lower screen's letters jump too.
    onNodeWithTag("touch:filter:All").performClick()
    assertFocused("game:1")
    onNodeWithTag("touch:jump:B").performClick()
    assertFocused("game:2")
  }

  private companion object {
    fun platform(id: Int, name: String, roms: Int) =
      """{"id": $id, "slug": "p$id", "fs_slug": "p$id", "name": "$name",
      "display_name": "$name", "rom_count": $roms, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    fun rom(id: Int, platform: Int, name: String, day: Int) =
      """{"id": $id, "platform_id": $platform, "platform_slug": "p$platform",
      "platform_fs_slug": "p$platform", "platform_display_name": "P$platform", "name": "$name",
      "fs_name": "$name.bin", "fs_name_no_tags": "$name", "fs_name_no_ext": "$name",
      "fs_extension": "bin", "fs_size_bytes": 1024, "path_cover_small": "", "url_cover": "",
      "created_at": "2024-01-0${day}T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
