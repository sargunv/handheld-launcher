package dev.sargunv.handheld.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.settings.LibrarySettings
import dev.sargunv.handheld.data.settings.LibraryTab
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.ui.assertFocused
import dev.sargunv.handheld.ui.awaitTag
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.collection
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.lastPlayed
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class LibraryTest {
  private val temp = TemporaryFolder().apply { create() }

  @AfterTest fun cleanUp() = temp.delete()

  /**
   * A server with two platforms of games and an empty third. On the Game Boy: Alpha, Bravo and
   * Zulu, with Zulu added first and Bravo last. On the NES: Contra and Metroid. With [collections],
   * three collections: Couch holds Bravo and Contra, Solo holds Alpha, and Zero holds nothing.
   */
  private fun server(collections: Boolean = true) = fakeRomm { path, request ->
    json(
      when (path) {
        "platforms" ->
          "[${platform(1, "p1", "Game Boy", 3)}, ${platform(2, "p2", "NES", 2)}, " +
            "${platform(3, "p3", "N64", 0)}]"
        "roms" ->
          if (request.lastPlayed) page(emptyList())
          else {
            page(
              listOf(
                game(1, 1, "Alpha", day = 2),
                game(2, 1, "Bravo", day = 3),
                game(3, 1, "Zulu", day = 1),
                game(4, 2, "Contra", day = 4),
                game(5, 2, "Metroid", day = 5),
              ),
            )
          }
        "roms/identifiers" -> "[1, 2, 3, 4, 5]"
        "collections" ->
          if (collections)
            "[${collection(1, "Couch", listOf(2, 4))}, ${collection(2, "Solo", listOf(1))}, " +
              "${collection(3, "Zero", emptyList())}]"
          else "[]"
        "collections/smart" -> "[]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  /** Launches and goes to the Library once the sync has filled it. */
  private fun ComposeUiTest.openLibrary(
    settings: Settings = Settings(),
    storage: File? = null,
    collections: Boolean = true,
  ): HandheldData {
    val data =
      launchApp(
        temp,
        server(collections),
        storage = listOfNotNull(storage?.let { StorageTarget(it.path, "Test storage") }),
        settings = settings,
      )
    awaitTag("shelf:RecentlyAdded")
    press(listOf(Key.ButtonStart, Key.DirectionDown, Key.ButtonA))
    awaitTag("place:Library")
    return data
  }

  /** Launches and opens the Game Boy's games. */
  private fun ComposeUiTest.openGameBoy(storage: File? = null) {
    openLibrary(storage = storage)
    assertFocused("library:platform:1")
    press(Key.ButtonA)
    awaitTag("shelf:platform:1")
  }

  @Test
  fun theListOpensAPlatformAndBackReturnsToIt() = runComposeUiTest {
    openLibrary()
    // The empty platform isn't listed.
    assertFocused("library:platform:1")
    onNodeWithTag("library:platform:3").assertDoesNotExist()
    onNodeWithTag("library:platform:1")
      .assertContentDescriptionEquals("Game Boy, 3 games, 0 on this device")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    press(Key.DirectionRight)
    assertFocused("library:platform:2")
    press(Key.ButtonA)
    awaitTag("shelf:platform:2")
    assertFocused("game:4")
    press(Key.ButtonB)
    assertFocused("library:platform:2")
    // A screen reader opens a platform the same way.
    onNodeWithTag("library:platform:1").performSemanticsAction(SemanticsActions.OnClick)
    awaitTag("shelf:platform:1")
  }

  @Test
  fun shoulderButtonsStepBetweenPlatformsAndBackFocusesTheLastOne() = runComposeUiTest {
    openGameBoy()
    assertFocused("game:1")
    press(Key.DirectionRight)
    assertFocused("game:2")
    press(Key.ButtonR1)
    awaitTag("shelf:platform:2")
    assertFocused("game:4")
    // At the last platform, R stays put rather than going on to Downloads.
    press(Key.ButtonR1)
    assertFocused("game:4")
    onNodeWithText("Queue empty").assertDoesNotExist()
    // Each platform keeps its own focus.
    press(Key.ButtonL1)
    assertFocused("game:2")
    press(Key.ButtonR1)
    assertFocused("game:4")
    press(Key.ButtonB)
    assertFocused("library:platform:2")
  }

  @Test
  fun aGameOpensItsScreenAndBackComesToIt() = runComposeUiTest {
    openGameBoy()
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    assertFocused("primary")
    press(Key.ButtonB)
    assertFocused("game:2")
    press(Key.ButtonB)
    assertFocused("library:platform:1")
  }

  @Test
  fun theMenuSortsAndJumpsToALetter() = runComposeUiTest {
    openGameBoy()
    assertFocused("game:1")
    // The first choice sorts by recently added, which starts again at the newest game.
    press(Key.ButtonX)
    assertFocused("menu:sort:RecentlyAdded")
    press(Key.ButtonA)
    assertFocused("game:2")
    // Sorting by name again comes back to the game that had focus then.
    press(listOf(Key.ButtonX, Key.ButtonA))
    assertFocused("game:1")
    // Past the sort and the filters come the letters: A, B and Z.
    press(Key.ButtonX)
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
    press(listOf(Key.DirectionLeft, Key.DirectionLeft))
    assertFocused("menu:jump:A")
    press(listOf(Key.DirectionRight, Key.DirectionRight, Key.ButtonA))
    assertFocused("game:3")
  }

  @Test
  fun opensOnPlatformsAndShoulderButtonsSwitchTabsStoppingAtTheEnds() = runComposeUiTest {
    val data = openLibrary()
    onNodeWithTag("library:tab:Platforms").assertIsSelected()
    assertFocused("library:platform:1")
    press(Key.ButtonL1)
    onNodeWithTag("library:tab:OnDevice").assertIsSelected()
    awaitText("No games")
    // At the first tab, L stays.
    press(Key.ButtonL1)
    onNodeWithTag("library:tab:OnDevice").assertIsSelected()
    press(listOf(Key.ButtonR1, Key.ButtonR1, Key.ButtonR1))
    onNodeWithTag("library:tab:AllGames").assertIsSelected()
    // All games is the whole library, in title order.
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.DirectionRight))
    assertFocused("game:4")
    // At the last tab, R stays.
    press(Key.ButtonR1)
    onNodeWithTag("library:tab:AllGames").assertIsSelected()
    assertFocused("game:4")
    assertEquals(LibraryTab.AllGames, data.settings.settings.value.library.tab)
    // Coming back to a tab focuses what it had focused.
    press(listOf(Key.ButtonL1, Key.ButtonL1))
    assertFocused("library:platform:1")
    press(Key.ButtonR1)
    press(Key.ButtonR1)
    assertFocused("game:4")
  }

  @Test
  fun opensOnTheTabItWasLastLeftOn() = runComposeUiTest {
    openLibrary(Settings(library = LibrarySettings(tab = LibraryTab.Collections)))
    onNodeWithTag("library:tab:Collections").assertIsSelected()
    assertFocused("library:collection:Regular:1")
  }

  @Test
  fun onDeviceHoldsOnlyTheGamesOnTheDevice() = runComposeUiTest {
    val storage = temp.newFolder()
    File(storage, "ROMs/p1").mkdirs()
    File(storage, "ROMs/p1/Zulu.bin").writeText("game")
    openLibrary(Settings(library = LibrarySettings(tab = LibraryTab.OnDevice)), storage = storage)
    assertFocused("game:3")
    onNodeWithTag("game:1").assertDoesNotExist()
    // Every game here is on the device, so nothing filters them.
    onNodeWithTag("touch:filter:OnDevice").assertDoesNotExist()
    press(Key.ButtonX)
    assertFocused("menu:sort:RecentlyAdded")
    onNodeWithTag("menu:filter:OnDevice").assertDoesNotExist()
  }

  @Test
  fun collectionsOpenTheirGamesAndShoulderButtonsStepBetweenThem() = runComposeUiTest {
    openLibrary()
    press(Key.ButtonR1)
    onNodeWithTag("library:tab:Collections").assertIsSelected()
    assertFocused("library:collection:Regular:1")
    onNodeWithTag("library:collection:Regular:1")
      .assertContentDescriptionEquals("Couch, 2 games, 0 on this device")
    press(Key.ButtonA)
    awaitTag("shelf:collection:Regular:1")
    // Couch's games in title order: Bravo, then Contra.
    assertFocused("game:2")
    onNodeWithTag("game:1").assertDoesNotExist()
    press(Key.ButtonR1)
    awaitTag("shelf:collection:Regular:2")
    assertFocused("game:1")
    // An empty collection is there too, as on Home, and opens to say so.
    press(Key.ButtonR1)
    awaitTag("shelf:collection:Regular:3")
    awaitText("No games")
    // At the last collection, R stays.
    press(Key.ButtonR1)
    onNodeWithTag("shelf:collection:Regular:3").assertExists()
    // Back returns to the tab, on the collection it was in.
    press(Key.ButtonB)
    assertFocused("library:collection:Regular:3")
    onNodeWithTag("library:tab:Collections").assertIsSelected()
  }

  @Test
  fun aPlatformsMenuOpensOpenWithForItsGames() = runComposeUiTest {
    openLibrary()
    assertFocused("library:platform:1")
    press(Key.ButtonX)
    assertFocused("menu:open-with")
    press(Key.ButtonA)
    awaitText("Open Game Boy games with")
    press(Key.ButtonB)
    assertFocused("library:platform:1")
  }

  @Test
  fun aCollectionsMenuOpensOpenWithForEachOfItsPlatforms() = runComposeUiTest {
    openLibrary()
    press(Key.ButtonR1)
    assertFocused("library:collection:Regular:1")
    // Couch holds a Game Boy game and an NES game, which open in different apps.
    press(Key.ButtonX)
    assertFocused("menu:open-with:p1")
    onNodeWithTag("menu:open-with:p1").assertTextEquals("Open Game Boy games with")
    onNodeWithTag("menu:open-with:p2").assertTextEquals("Open NES games with")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Open NES games in Couch with")
    onNodeWithText("Chosen for NES games in Couch").assertExists()
    awaitText("Collection 'Couch': none")
    press(Key.ButtonB)
    assertFocused("library:collection:Regular:1")
    // Solo's games are all on one platform.
    press(listOf(Key.DirectionRight, Key.ButtonX))
    assertFocused("menu:open-with:p1")
    onNodeWithTag("menu:open-with:p1").assertTextEquals("Open with")
    onNodeWithTag("menu:open-with:p2").assertDoesNotExist()
  }

  @Test
  fun theMenuHintShowsOnlyWithSomethingForTheMenuToDo() = runComposeUiTest {
    openLibrary(collections = false)
    assertFocused("library:platform:1")
    onNodeWithTag("hint:Menu").assertExists()
    press(Key.ButtonR1)
    awaitText("No collections")
    onNodeWithTag("hint:Menu").assertDoesNotExist()
    onNodeWithTag("hint:Confirm").assertDoesNotExist()
    press(Key.ButtonX)
    onNodeWithTag("menu:open-with").assertDoesNotExist()
    // On device has no games to sort.
    press(listOf(Key.ButtonL1, Key.ButtonL1))
    awaitText("No games")
    onNodeWithTag("hint:Menu").assertDoesNotExist()
  }

  @Test
  fun coversShowWhichGamesAreOnTheDeviceAndTheLowerScreenFilters() = runComposeUiTest {
    // Zulu is in its platform's folder.
    val storage = temp.newFolder()
    File(storage, "ROMs/p1").mkdirs()
    File(storage, "ROMs/p1/Zulu.bin").writeText("game")
    openLibrary(storage = storage)
    // The list and its lower screen count the games on the device.
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("1 on this device").fetchSemanticsNodes().size == 1
    }
    assertFocused("library:platform:1")
    onNodeWithTag("library:platform:1")
      .assertContentDescriptionEquals("Game Boy, 3 games, 1 on this device")
    press(Key.ButtonA)
    assertFocused("game:1")
    // The covers mark it, and say so to a screen reader.
    onNodeWithTag("onDevice:3", useUnmergedTree = true).assertExists()
    onNodeWithTag("onDevice:1", useUnmergedTree = true).assertDoesNotExist()
    onNodeWithTag("game:3").assert(onDevice("On this device"))
    onNodeWithTag("game:1").assert(onDevice("Not on this device"))
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

  @Test
  fun aFilterThatLeavesNothingClearsTheFocusedGame() = runComposeUiTest {
    openGameBoy()
    assertFocused("game:1")
    onNodeWithText("Open").assertExists()
    onNodeWithTag("touch:filter:OnDevice").performClick()
    waitForIdle()
    onNodeWithText("No matches").assertExists()
    onNodeWithText("Open").assertDoesNotExist()
    onNodeWithText("Alpha").assertDoesNotExist()
  }

  private companion object {
    fun onDevice(state: String) =
      SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    fun game(id: Int, platform: Int, name: String, day: Int) =
      rom(id, name, platform, "p$platform", "$name.bin", added = "2024-01-0$day")
  }
}
