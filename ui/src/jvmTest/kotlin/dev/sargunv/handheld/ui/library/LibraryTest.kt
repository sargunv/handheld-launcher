package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibraryTest {
  /**
   * A server with two platforms of games and an empty third. On the Game Boy: Alpha, Bravo and
   * Zulu, with Zulu added first and Bravo last. On the NES: Contra and Metroid. With [collections],
   * two collections: Couch holds Bravo and Contra, and Solo holds Alpha.
   */
  private fun server(collections: Boolean = true) = {
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
          "collections" ->
            if (collections)
              "[${collection(1, "Couch", listOf(2, 4))}, ${collection(2, "Solo", listOf(1))}]"
            else "[]"
          "collections/smart" -> "[]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  private fun ComposeUiTest.launch(
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
    storage: File? = null,
    collections: Boolean = true,
  ) {
    val engine = server(collections)
    setContent {
      val heldKeys = remember { HeldKeys() }
      val credentials = remember {
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token"))
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
          listOfNotNull(storage?.let { StorageTarget(it.path, "Test storage") }),
        ),
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        initialSettings = settings,
        saveSettings = saveSettings,
        httpEngine = engine,
      )
    }
  }

  /** Launches and goes to the Library once the sync has filled it. */
  private fun ComposeUiTest.openLibrary(
    settings: Settings = Settings(),
    saveSettings: suspend (Settings) -> Unit = {},
    storage: File? = null,
    collections: Boolean = true,
  ) {
    launch(settings, saveSettings, storage, collections)
    awaitTag("shelf:RecentlyAdded")
    press(listOf(Key.ButtonStart, Key.DirectionDown, Key.ButtonA))
    awaitTag("place:Library")
  }

  /** Launches and opens the Game Boy's games. */
  private fun ComposeUiTest.openGameBoy(storage: File? = null) {
    openLibrary(storage = storage)
    assertFocused("library:platform:1")
    press(Key.ButtonA)
    awaitTag("shelf:platform:1")
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

  private fun ComposeUiTest.awaitText(text: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.assertFocused(tag: String) {
    awaitTag(tag)
    onNodeWithTag(tag).assertIsFocused()
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
    onNodeWithText("Nothing is downloading.").assertDoesNotExist()
    // Each platform keeps its own focus.
    press(Key.ButtonL1)
    assertFocused("game:2")
    press(Key.ButtonR1)
    assertFocused("game:4")
    press(Key.ButtonB)
    assertFocused("library:platform:2")
  }

  @Test
  fun backOnTheListGoesHome() = runComposeUiTest {
    openLibrary()
    assertFocused("library:platform:1")
    // The shoulder buttons switch tabs, and coming back focuses the same platform.
    press(listOf(Key.ButtonR1, Key.ButtonL1))
    assertFocused("library:platform:1")
    press(Key.ButtonB)
    awaitTag("shelf:RecentlyAdded")
    onNodeWithTag("place:Home").assertExists()
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
    val saved = mutableListOf<Settings>()
    openLibrary(saveSettings = { saved += it })
    onNodeWithTag("library:tab:Platforms").assertIsSelected()
    assertFocused("library:platform:1")
    press(Key.ButtonL1)
    onNodeWithTag("library:tab:OnDevice").assertIsSelected()
    awaitText("No games on this device yet.")
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
    assertEquals(LibraryTab.AllGames, saved.last().library.tab)
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
    val storage = createTempDirectory("library-storage").toFile()
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
    // At the last collection, R stays.
    press(Key.ButtonR1)
    assertFocused("game:1")
    // Back returns to the tab, on the collection it was in.
    press(Key.ButtonB)
    assertFocused("library:collection:Regular:2")
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
    awaitText("Your server has no collections with games.")
    onNodeWithTag("hint:Menu").assertDoesNotExist()
    onNodeWithTag("hint:Confirm").assertDoesNotExist()
    press(Key.ButtonX)
    onNodeWithTag("menu:open-with").assertDoesNotExist()
    // On device has no games to sort.
    press(listOf(Key.ButtonL1, Key.ButtonL1))
    awaitText("No games on this device yet.")
    onNodeWithTag("hint:Menu").assertDoesNotExist()
  }

  @Test
  fun coversShowWhichGamesAreOnTheDeviceAndTheLowerScreenFilters() = runComposeUiTest {
    // Zulu is in its platform's folder.
    val storage = createTempDirectory("library-storage").toFile()
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
    onNodeWithText("No games here match the filter.").assertExists()
    onNodeWithText("Open").assertDoesNotExist()
    onNodeWithText("Alpha").assertDoesNotExist()
  }

  private companion object {
    fun onDevice(state: String) =
      SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

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

    fun collection(id: Int, name: String, roms: List<Int>) =
      """{"id": $id, "name": "$name", "rom_ids": $roms, "rom_count": ${roms.size}, "user_id": 1,
      "owner_username": "me", "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
