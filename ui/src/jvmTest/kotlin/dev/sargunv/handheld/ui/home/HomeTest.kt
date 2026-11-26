package dev.sargunv.handheld.ui.home

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

@OptIn(ExperimentalTestApi::class)
class HomeTest {
  /**
   * A server with three games on one platform. The user played game 2 last and game 1 before it,
   * and game 3 was added most recently. One collection holds games 1 and 2.
   */
  private fun server(fail: Boolean = false, played: Boolean = true) = {
    MockEngine { request ->
      if (fail) return@MockEngine respondError(HttpStatusCode.InternalServerError)
      val body =
        when (request.url.encodedPath.substringAfter("/api/")) {
          "platforms" -> "[$Platform]"
          "roms" ->
            if (request.url.parameters["last_played"] == "true") {
              page(if (played) listOf(rom(2), rom(1)) else emptyList())
            } else page(listOf(rom(1), rom(2), rom(3)))
          "roms/identifiers" -> "[1, 2, 3]"
          "collections" -> "[$Collection]"
          "collections/smart" -> "[]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  private fun ComposeUiTest.launch(
    fail: Boolean = false,
    played: Boolean = true,
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
  ) {
    setContent {
      val heldKeys = remember { HeldKeys() }
      App(
        DualScreenLayout.Separate,
        heldKeys,
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token")),
        TestDevice,
        remember { LibraryCache.open(path = null) },
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        initialSettings = settings,
        saveSettings = saveSettings,
        httpEngine = server(fail, played),
      )
    }
  }

  private fun ComposeUiTest.awaitTag(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

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

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  private fun ComposeUiTest.assertFocused(tag: String) = onNodeWithTag(tag).assertIsFocused()

  @Test
  fun focusesTheLastPlayedGameFirst() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    assertFocused("continue:2")
    press(Key.DirectionDown)
    assertFocused("RecentlyPlayed:1")
    press(Key.DirectionDown)
    assertFocused("collection:Regular:1")
    press(Key.DirectionDown)
    // The newest game comes first.
    assertFocused("RecentlyAdded:3")
  }

  @Test
  fun shoulderButtonsSwitchPlacesAndFocusComesBack() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
    assertFocused("RecentlyAdded:2")
    press(Key.ButtonR1)
    awaitTag("shelf:platform:1")
    // Library has one platform, so R goes on to Downloads.
    press(Key.ButtonR1)
    onNodeWithText("Downloads are coming soon.").assertExists()
    // Downloads is the last place.
    press(Key.ButtonR1)
    onNodeWithText("Downloads are coming soon.").assertExists()
    press(listOf(Key.ButtonL1, Key.ButtonL1))
    assertFocused("RecentlyAdded:2")
  }

  @Test
  fun aGameOpensItsScreenAndBackComesHome() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonA)
    onNodeWithText("The game screen is coming soon.").assertExists()
    press(Key.ButtonB)
    assertFocused("continue:2")
  }

  @Test
  fun theMenuHidesAShelfAndShowsItAgain() = runComposeUiTest {
    val saved = mutableListOf<Settings>()
    launch(saveSettings = { saved += it })
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonX))
    assertFocused("menu:hide")
    press(Key.ButtonA)
    onNodeWithTag("shelf:Collections").assertDoesNotExist()
    assertEquals(setOf(HomeShelf.Collections), saved.last().home.hidden)

    // Focus went back to the first game, which isn't on a shelf, so the menu only shows shelves.
    assertFocused("continue:2")
    press(Key.ButtonX)
    assertFocused("menu:show:Collections")
    press(Key.ButtonA)
    awaitTag("shelf:Collections")
    assertEquals(HomeSettings(), saved.last().home)
  }

  @Test
  fun theMenuShowsHiddenShelvesWhenNothingElseShows() = runComposeUiTest {
    val hidden = HomeSettings().hide(HomeShelf.Collections).hide(HomeShelf.RecentlyAdded)
    launch(played = false, settings = Settings(home = hidden))
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText("No shelves", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
    press(Key.ButtonX)
    assertFocused("menu:show:Collections")
    press(Key.ButtonA)
    awaitTag("collection:Regular:1")
    assertFocused("collection:Regular:1")
  }

  @Test
  fun theMenuMovesAShelf() = runComposeUiTest {
    val saved = mutableListOf<Settings>()
    launch(saveSettings = { saved += it })
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.ButtonX))
    // Recently played is the first shelf, so it can only move down.
    assertFocused("menu:hide")
    onNodeWithTag("menu:up").assertDoesNotExist()
    press(listOf(Key.DirectionDown, Key.ButtonA))
    assertEquals(
      listOf(HomeShelf.Collections, HomeShelf.RecentlyPlayed, HomeShelf.RecentlyAdded),
      saved.last().home.visible,
    )
    // Focus stays on the shelf, in its new place.
    assertFocused("RecentlyPlayed:1")
  }

  @Test
  fun saysSoWhenTheFirstSyncFails() = runComposeUiTest {
    launch(fail = true)
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText("Couldn't reach your server to sync your library."))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  private companion object {
    const val Platform =
      """{"id": 1, "slug": "gb", "fs_slug": "gb", "name": "Game Boy", "display_name": "Game Boy",
      "rom_count": 3, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    const val Collection =
      """{"id": 1, "name": "Favourites", "rom_ids": [1, 2], "rom_count": 2, "user_id": 1,
      "owner_username": "me", "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    fun rom(id: Int) =
      """{"id": $id, "platform_id": 1, "platform_slug": "gb", "platform_fs_slug": "gb",
      "platform_display_name": "Game Boy", "name": "Game $id", "fs_name": "Game $id.gb",
      "fs_name_no_tags": "Game $id", "fs_name_no_ext": "Game $id", "fs_extension": "gb",
      "fs_size_bytes": 1024, "path_cover_small": "", "url_cover": "",
      "created_at": "2024-01-0${id}T00:00:00+00:00", "updated_at": "2024-01-01T00:00:00+00:00",
      "rom_user": {"last_played": "2025-0${id}-01T00:00:00+00:00"}}"""

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
