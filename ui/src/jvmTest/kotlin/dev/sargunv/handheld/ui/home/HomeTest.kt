package dev.sargunv.handheld.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
import dev.sargunv.handheld.ui.places.HomePress
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.settings.SettingsSection
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
  /** The latest press of the system's HOME, which [pressHome] makes. */
  private var homePress by mutableStateOf<HomePress?>(null)

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
    open: OpenRequest? = null,
  ) {
    // Made once: a new engine would make a new app state when HOME recomposes the content.
    val engine = server(fail, played)
    setContent {
      val heldKeys = remember { HeldKeys() }
      val library = remember { LibraryCache.open(path = null) }
      val credentials = remember {
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token"))
      }
      App(
        DualScreenLayout.Separate,
        heldKeys,
        credentials,
        TestDevice,
        library,
        rememberTestDownloads(library, credentials),
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        initialSettings = settings,
        saveSettings = saveSettings,
        httpEngine = engine,
        openRequest = open,
        homePress = homePress,
      )
    }
  }

  private fun ComposeUiTest.pressHome() {
    homePress = HomePress()
    waitForIdle()
  }

  private fun ComposeUiTest.awaitFocused(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
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

  /** Goes to [place] from Home with the main menu, as the player would. */
  private fun ComposeUiTest.goFromHome(place: Place) {
    press(Key.ButtonStart)
    awaitTag("main:Home")
    press(List(place.ordinal) { Key.DirectionDown } + Key.ButtonA)
    awaitTag("place:${place.name}")
  }

  private fun ComposeUiTest.assertFocused(tag: String) = onNodeWithTag(tag).assertIsFocused()

  @Test
  fun coversHeadersAndHintsDescribeThemselvesToAccessibilityServices() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    // A cover is one button, which says the game's title once and whether it's on the device.
    onNodeWithTag("continue:2")
      .assertContentDescriptionEquals("Game 2, not on this device")
      .assert(expectValue(SemanticsProperties.Role, Role.Button))
      .assertHasClickAction()
    onAllNodesWithText("Game 2").assertCountEquals(0)
    onNodeWithText("Continue playing").assert(isHeading())
    onNodeWithTag("place:Home").assert(isHeading()).assertTextEquals("Home")
    // Each hint is one element: the button, then what it does.
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Open")
  }

  @Test
  fun aScreenReaderOpensTheFocusedGame() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    onNodeWithTag("continue:2").performSemanticsAction(SemanticsActions.OnClick)
    awaitTag("primary")
    assertFocused("primary")
    onNodeWithTag("primary").assert(expectValue(SemanticsProperties.Role, Role.Button))
    onNodeWithText("Game 2").assert(isHeading())
  }

  @Test
  fun aScreenReaderPicksAPlaceInTheMainMenu() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonStart)
    awaitTag("main:Downloads")
    onNodeWithTag("main:Home").assertIsSelected()
    onNodeWithTag("main:Downloads").assertIsNotSelected()
    onNodeWithTag("main:Downloads").performSemanticsAction(SemanticsActions.OnClick)
    awaitTag("place:Downloads")
    onNodeWithText("Nothing is downloading.").assertExists()
    onNodeWithTag("main:Downloads").assertDoesNotExist()
  }

  @Test
  fun aScreenReaderCantPressWhatsBehindAMenu() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonStart)
    awaitTag("main:Home")
    onNodeWithTag("main:Settings").assertHasClickAction()
    onNodeWithTag("continue:2").assertHasNoClickAction()
  }

  @Test
  fun anOpenRequestShowsTheScreenItNames() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.Game(3)))
    awaitTag("primary")
    onNodeWithText("Game 3").assert(isHeading())
    // Back goes to the places, as if the player had come from Home.
    press(Key.ButtonB)
    awaitTag("continue:2")
  }

  @Test
  fun anOpenRequestForAPlaceSwitchesToIt() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.AtPlace(Place.Downloads)))
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Nothing is downloading.").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag("place:Downloads").assertExists()
  }

  @Test
  fun anOpenRequestForAPlatformOpensItsLibraryShelf() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.LibraryShelf("gb")))
    // The shelf's games, rather than the list of shelves.
    awaitTag("shelf:platform:1")
    onNodeWithTag("place:Library").assertExists()
  }

  @Test
  fun anOpenRequestForLibraryShowsItsShelves() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.LibraryShelf(null)))
    awaitTag("library:shelves")
    onNodeWithTag("place:Library").assertExists()
  }

  @Test
  fun anOpenRequestForASettingsSectionFocusesIt() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.Settings(SettingsSection.Storage)))
    awaitTag("settings:Storage")
    awaitFocused("settings:Storage")
    onNodeWithTag("settings:storage:note").assertExists()
  }

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
  fun theMainMenuGoesToPlacesAndBackComesHome() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
    assertFocused("RecentlyAdded:2")
    // Focus starts on the place showing.
    press(Key.ButtonStart)
    awaitTag("main:Home")
    assertFocused("main:Home")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitTag("library:platform:1")
    onNodeWithTag("place:Library").assertExists()
    // Start closes the menu too, leaving focus where it was.
    press(Key.ButtonStart)
    assertFocused("main:Library")
    press(Key.ButtonStart)
    onNodeWithTag("main:Library").assertDoesNotExist()
    assertFocused("library:platform:1")
    // L and R no longer switch places.
    press(Key.ButtonR1)
    onNodeWithTag("place:Library").assertExists()
    // Back goes Home, with focus where it was.
    press(Key.ButtonB)
    onNodeWithTag("place:Home").assertExists()
    assertFocused("RecentlyAdded:2")
    // On Home, Back does nothing.
    press(Key.ButtonB)
    onNodeWithTag("place:Home").assertExists()
    assertFocused("RecentlyAdded:2")
  }

  @Test
  fun theMainMenuLeavesAGameForAPlace() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonA)
    awaitTag("primary")
    // Nothing covered by the game is the current place.
    press(Key.ButtonStart)
    awaitTag("main:Home")
    onNodeWithTag("main:Home").assertIsNotSelected()
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("place:Settings")
    onNodeWithTag("primary").assertDoesNotExist()
    // Back from Settings goes Home, not to the game.
    press(Key.ButtonB)
    awaitFocused("continue:2")
  }

  @Test
  fun aGameOpensItsScreenAndBackComesHome() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonA)
    // The primary action has focus first.
    awaitTag("primary")
    assertFocused("primary")
    onNode(hasTestTag("primary") and hasText("Download (1.0 KB)")).assertExists()
    press(Key.ButtonB)
    assertFocused("continue:2")
  }

  @Test
  fun homeStartsOverOnHomeWithFocusOnItsFirstItem() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
    assertFocused("RecentlyAdded:2")
    goFromHome(Place.Library)
    awaitTag("library:platform:1")
    pressHome()
    onNodeWithTag("place:Home").assertExists()
    awaitFocused("continue:2")
  }

  @Test
  fun homeOnHomeGoesBackToItsFirstItem() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
    assertFocused("RecentlyAdded:2")
    pressHome()
    awaitFocused("continue:2")
  }

  @Test
  fun homeLeavesAGameForHome() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitTag("primary")
    pressHome()
    awaitFocused("continue:2")
    onNodeWithTag("primary").assertDoesNotExist()
  }

  @Test
  fun homeClosesAnOpenDialogFirst() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    goFromHome(Place.Library)
    awaitTag("library:platform:1")
    press(Key.ButtonStart)
    awaitTag("main:Home")
    pressHome()
    onNodeWithTag("main:Home").assertDoesNotExist()
    onNodeWithTag("place:Library").assertExists()
    assertFocused("library:platform:1")
    // The next press goes Home.
    pressHome()
    onNodeWithTag("place:Home").assertExists()
    awaitFocused("continue:2")
  }

  @Test
  fun aDialogOpenedAfterHomeStaysOpen() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    pressHome()
    press(Key.ButtonStart)
    awaitTag("main:Home")
    waitForIdle()
    onNodeWithTag("main:Home").assertExists()
  }

  private fun ComposeUiTest.awaitText(text: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  @Test
  fun aGameQueuesFromItsMenuAndDownloadsCancelsIt() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonA)
    awaitTag("primary")
    press(Key.ButtonX)
    assertFocused("menu:download")
    press(listOf(Key.ButtonA, Key.ButtonB))
    // The main menu counts the queue.
    press(Key.ButtonStart)
    awaitTag("main:Downloads")
    onNodeWithTag("main:Downloads").assertContentDescriptionEquals("Downloads, 1 in the queue")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("download:2")
    // The test server doesn't serve the game, so the download fails and waits for a retry.
    awaitText("The server didn't find it")
    press(Key.ButtonX)
    assertFocused("menu:retry")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Nothing is downloading.")
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
