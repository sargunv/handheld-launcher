package dev.sargunv.handheld.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.assertFocused
import dev.sargunv.handheld.ui.awaitFocused
import dev.sargunv.handheld.ui.awaitTag
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.collection
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.lastPlayed
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.places.HomePress
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import dev.sargunv.handheld.ui.settings.SettingsSection
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
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
  private fun server(fail: Boolean = false, played: Boolean = true) = fakeRomm { path, request ->
    if (fail) return@fakeRomm respondError(HttpStatusCode.InternalServerError)
    json(
      when (path) {
        "platforms" -> "[${platform(roms = 3)}]"
        "roms" ->
          if (request.lastPlayed) page(if (played) listOf(game(2), game(1)) else emptyList())
          else page(listOf(game(1), game(2), game(3)))
        "roms/identifiers" -> "[1, 2, 3]"
        "collections" -> "[${collection(1, "Favourites", listOf(1, 2))}]"
        "collections/smart" -> "[]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  private fun ComposeUiTest.launch(
    fail: Boolean = false,
    played: Boolean = true,
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
    open: OpenRequest? = null,
  ) {
    launchApp(
      server(fail, played),
      settings = settings,
      saveSettings = saveSettings,
      open = open,
      homePress = { homePress },
      layout = DualScreenLayout.Separate,
    )
  }

  private fun ComposeUiTest.pressHome() {
    homePress = HomePress()
    waitForIdle()
  }

  /** Goes to [place] from Home with the main menu, as the player would. */
  private fun ComposeUiTest.goFromHome(place: Place) {
    press(Key.ButtonStart)
    awaitTag("main:Home")
    press(List(place.ordinal) { Key.DirectionDown } + Key.ButtonA)
    awaitTag("place:${place.name}")
  }

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
    onNodeWithText("Queue empty").assertExists()
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
      onAllNodesWithText("Queue empty").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag("place:Downloads").assertExists()
  }

  @Test
  fun anOpenRequestForAPlatformOpensItsLibraryShelf() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.LibraryPlatform("gb")))
    // The shelf's games, rather than the list of shelves.
    awaitTag("shelf:platform:1")
    onNodeWithTag("place:Library").assertExists()
    // Back goes to the platforms.
    press(Key.ButtonB)
    awaitFocused("library:platform:1")
  }

  @Test
  fun anOpenRequestForACollectionOpensItsLibraryShelf() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.LibraryCollection(CollectionKind.Regular, 1)))
    awaitTag("shelf:collection:Regular:1")
    press(Key.ButtonB)
    awaitFocused("library:collection:Regular:1")
  }

  @Test
  fun anOpenRequestForLibraryShowsItsTab() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.Library(null)))
    awaitTag("library:shelves")
    onNodeWithTag("place:Library").assertExists()
    onNodeWithTag("library:tab:Platforms").assertIsSelected()
  }

  @Test
  fun anOpenRequestForALibraryTabShowsIt() = runComposeUiTest {
    launch(open = OpenRequest(OpenTarget.Library(LibraryTab.AllGames)))
    awaitTag("library:grid")
    onNodeWithTag("library:tab:AllGames").assertIsSelected()
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
    // L and R switch the Library's tabs, not places.
    press(Key.ButtonR1)
    onNodeWithTag("place:Library").assertExists()
    onNodeWithTag("library:tab:Collections").assertIsSelected()
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
    // The game being played isn't on the device, so Confirm opens its page rather than playing.
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Open")
    press(Key.ButtonA)
    // The primary action has focus first.
    awaitTag("primary")
    assertFocused("primary")
    onNode(hasTestTag("primary") and hasText("Download (1.0 KB)")).assertExists()
    press(Key.ButtonB)
    assertFocused("continue:2")
  }

  @Test
  fun theMenuOnTheGameBeingPlayedGoesToItsPage() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    onNodeWithTag("hint:Menu").assertExists()
    press(Key.ButtonX)
    assertFocused("menu:game")
    onNodeWithTag("menu:open-with").assertExists()
    press(Key.ButtonA)
    awaitTag("primary")
    onNodeWithText("Game 2").assert(isHeading())
    press(Key.ButtonB)
    awaitFocused("continue:2")
  }

  @Test
  fun aCollectionOpensItsGamesAndBackComesHome() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(listOf(Key.DirectionDown, Key.DirectionDown))
    assertFocused("collection:Regular:1")
    onNodeWithTag("collection:Regular:1")
      .assertContentDescriptionEquals("Favourites, 2 games")
      .assertHasClickAction()
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Open")
    press(Key.ButtonA)
    awaitTag("shelf:collection:Regular:1")
    onNodeWithTag("place:Library").assertExists()
    awaitFocused("game:1")
    onNodeWithTag("game:3").assertDoesNotExist()
    onNodeWithTag("hint:Back").assertTextEquals("B", "Home")
    press(Key.ButtonB)
    onNodeWithTag("place:Home").assertExists()
    awaitFocused("collection:Regular:1")
    // The Library shows the collection's tab, with it focused.
    goFromHome(Place.Library)
    onNodeWithTag("library:tab:Collections").assertIsSelected()
    awaitFocused("library:collection:Regular:1")
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

  @Test
  fun aGameQueuesAndDownloadsOpensAndCancelsIt() = runComposeUiTest {
    launch()
    awaitTag("continue:2")
    press(Key.ButtonA)
    awaitTag("primary")
    // Download is the primary action, so the menu doesn't repeat it.
    press(Key.ButtonX)
    assertFocused("menu:open-with")
    onNodeWithTag("menu:download").assertDoesNotExist()
    press(Key.ButtonB)
    assertFocused("primary")
    press(listOf(Key.ButtonA, Key.ButtonB))
    // The main menu counts the queue.
    press(Key.ButtonStart)
    awaitTag("main:Downloads")
    onNodeWithTag("main:Downloads").assertContentDescriptionEquals("Downloads, 1 in the queue")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("download:2")
    // The test server doesn't serve the game, so the download fails and waits for a retry.
    awaitText("The server didn't find it")
    // Confirm opens the game's page.
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Open")
    press(Key.ButtonA)
    awaitTag("primary")
    onNodeWithText("Game 2").assert(isHeading())
    press(Key.ButtonB)
    awaitFocused("download:2")
    press(Key.ButtonX)
    assertFocused("menu:retry")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Queue empty")
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

    // Focus went back to the game being played, whose menu shows the hidden shelves too.
    assertFocused("continue:2")
    press(Key.ButtonX)
    assertFocused("menu:game")
    press(listOf(Key.DirectionDown, Key.DirectionDown))
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
      onAllNodes(hasText("Offline") and !hasTestTag("sync:status"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  private companion object {
    /** Game [id], added on the [id]th of January and last played in month [id]. */
    fun game(id: Int) = rom(id, added = "2024-01-0$id", lastPlayed = "2025-0$id-01")
  }
}
