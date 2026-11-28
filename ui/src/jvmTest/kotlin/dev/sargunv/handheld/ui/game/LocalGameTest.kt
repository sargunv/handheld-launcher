package dev.sargunv.handheld.ui.game

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.Vault
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.settings.LibrarySettings
import dev.sargunv.handheld.data.settings.LibraryTab
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.ui.TestServer
import dev.sargunv.handheld.ui.assertFocused
import dev.sargunv.handheld.ui.awaitTag
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
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.rules.TemporaryFolder

/**
 * Local games: files in a platform's folder that match none of the server's games. The server has
 * Game 1 on the Game Boy and nothing on the N64; the device has Homebrew.gb and Demo.z64, which
 * match nothing, and a save beside Homebrew.gb, which isn't a game.
 */
@OptIn(ExperimentalTestApi::class)
class LocalGameTest {
  private val temp = TemporaryFolder().apply { create() }

  private val storage = temp.newFolder()

  private val homebrew = File(storage, "ROMs/gb/Homebrew.gb")
  private val demo = File(storage, "ROMs/n64/Demo.z64")
  private val library = LibraryCache.open(path = null)

  /** Whether the server has gained Homebrew, under the name of its file on the device. */
  @Volatile private var serverHasHomebrew = false

  /** Whether the server refuses uploads, as for an account that may not upload games. */
  @Volatile private var refuseUploads = false

  /** What an upload waits for before it starts, when there's something. */
  @Volatile private var uploadHold: CompletableDeferred<Unit>? = null

  /** The scopes the account's role grants, or null when the server doesn't say. */
  @Volatile private var accountScopes: String? = null

  /** The device's sign-in: a pasted token by default, whose scopes RomM doesn't report. */
  private val credentials = Vault.InMemory(RommCredentials(TestServer, "rmm_token", 1, "player"))

  /** The paths below `/api/` of the requests the server had about uploads and scans. */
  private val uploadRequests = Collections.synchronizedList(mutableListOf<String>())

  init {
    homebrew.parentFile.mkdirs()
    homebrew.writeText("homebrew")
    File(storage, "ROMs/gb/Homebrew.srm").writeText("save")
    demo.parentFile.mkdirs()
    demo.writeText("demo")
  }

  @AfterTest
  fun cleanUp() {
    library.close()
    temp.delete()
  }

  private val server = fakeRomm { path, request ->
    if (path.startsWith("roms/upload") || path.startsWith("tasks")) uploadRequests += path
    json(
      when (path) {
        "roms/upload/start" -> {
          uploadHold?.await()
          if (refuseUploads) {
            return@fakeRomm json("""{"detail": "Forbidden"}""", HttpStatusCode.Forbidden)
          }
          """{"upload_id": "u1"}"""
        }
        "roms/upload/u1" -> """{"received": 1, "total": 1}"""
        "roms/upload/u1/complete" -> {
          // The file is in the platform's folder on the server, which a scan adds.
          serverHasHomebrew = true
          "{}"
        }
        "tasks/scan",
        "tasks/t1" ->
          """{"task_key": null, "task_name": "scan", "task_id": "t1", "status": "finished",
          "created_at": null, "enqueued_at": null}"""
        "platforms" -> "[${platform()}, ${platform(2, "n64", "N64", roms = 0)}]"
        "roms" ->
          if (request.lastPlayed) page(emptyList())
          else page(listOfNotNull(rom(1), HomebrewRom.takeIf { serverHasHomebrew }))
        "roms/identifiers" -> if (serverHasHomebrew) "[1, 2]" else "[1]"
        "collections",
        "collections/smart" -> "[]"
        "saves" -> "[]"
        "users/me" ->
          accountScopes?.let {
            """{"id": 1, "username": "player", "role": "user", "oauth_scopes": $it,
            "created_at": "2024-01-01T00:00:00+00:00",
            "updated_at": "2024-01-01T00:00:00+00:00"}"""
          } ?: return@fakeRomm respondError(HttpStatusCode.NotFound)
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  private fun ComposeUiTest.launch(
    launcher: GameLauncher = FakeLauncher(),
    settings: Settings = Settings(),
    open: OpenTarget? = null,
  ): HandheldData =
    launchApp(
      temp,
      server,
      credentials = credentials,
      library = library,
      storage = listOf(StorageTarget(storage.path, "Test storage")),
      settings = settings,
      launcher = launcher,
      open = open,
    )

  /** Launches, and goes to the Library once the sync has filled it. */
  private fun ComposeUiTest.openLibrary(tab: LibraryTab = LibraryTab.Platforms) {
    launch(settings = Settings(library = LibrarySettings(tab = tab)))
    awaitTag("shelf:RecentlyAdded")
    press(listOf(Key.ButtonStart, Key.DirectionDown, Key.ButtonA))
    awaitTag("place:Library")
  }

  /** Launches, and opens Homebrew's page from the Game Boy's games. */
  private fun ComposeUiTest.openHomebrew(launcher: GameLauncher = FakeLauncher()): HandheldData {
    val data = launch(launcher, open = OpenTarget.Local(homebrew.path))
    awaitText("Play")
    onNodeWithTag("primary").assertIsFocused()
    return data
  }

  private val homebrewTag = "game:local:${homebrew.path}"
  private val demoTag = "game:local:${demo.path}"

  @Test
  fun localGamesShowInTheirPlatformMarkedAsNotOnTheServer() = runComposeUiTest {
    openLibrary()
    // The platforms count them, and the N64, with only a local game, is listed for it.
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasContentDescription("Game Boy, 2 games, 1 on this device"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    onNodeWithTag("library:platform:2")
      .assertContentDescriptionEquals("N64, 1 game, 1 on this device")
    assertFocused("library:platform:1")
    press(Key.ButtonA)
    assertFocused("game:1")
    onNodeWithTag(demoTag).assertDoesNotExist()
    // The save beside Homebrew isn't a game.
    onNodeWithText("Homebrew.srm", substring = true).assertDoesNotExist()
    onNodeWithTag(homebrewTag)
      .assert(hasContentDescription("Homebrew, not on your server"))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On this device"))
    onNodeWithTag("onDevice:local:${homebrew.path}", useUnmergedTree = true).assertExists()
    // The lower screen says so too.
    press(Key.DirectionRight)
    assertFocused(homebrewTag)
    onNodeWithTag("game:not-on-server").assertExists()
    onNodeWithText("Homebrew.gb", substring = true).assertExists()
  }

  @Test
  fun localGamesAreOnTheDeviceAndInAllGames() = runComposeUiTest {
    openLibrary(LibraryTab.OnDevice)
    assertFocused(demoTag)
    onNodeWithTag(homebrewTag).assertExists()
    // Game 1 isn't on the device.
    onNodeWithTag("game:1").assertDoesNotExist()
    press(listOf(Key.ButtonR1, Key.ButtonR1, Key.ButtonR1))
    // All games, by title: Demo, Game 1, Homebrew.
    assertFocused(demoTag)
    press(Key.DirectionRight)
    assertFocused("game:1")
    press(Key.DirectionRight)
    assertFocused(homebrewTag)
    press(Key.ButtonA)
    awaitText("Play")
    onNodeWithTag("primary").assertIsFocused()
  }

  @Test
  fun aLocalGamePlaysWithoutARomIdAndItsSavesStayOnTheDevice() = runComposeUiTest {
    val launcher = FakeLauncher()
    openHomebrew(launcher)
    onNodeWithText("Game Boy · 8 bytes · Not on your server").assertExists()
    onNodeWithTag("game:saves-local").assert(hasText("Saves kept on this device"))
    awaitText("Opens in Built-in · Gambatte")

    press(Key.ButtonA)
    awaitText("Played")
    assertEquals(homebrew.path, launcher.played.single())
    val game = launcher.games.single()
    assertEquals(GameKey.Local(homebrew.path), game.key)
    assertTrue(game is LocalGame)
    // The menu has what applies to a game on the device, and Open with for it.
    press(Key.ButtonX)
    awaitTag("menu:remove")
    onNodeWithTag("menu:upload").assertExists()
    onNodeWithTag("menu:open-with").assertExists()
    onNodeWithTag("menu:cancel").assertDoesNotExist()
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitText("Open Homebrew with")
    awaitText("Kept for this file")
  }

  // An upload sends one file, so a disc set, whose other files would stay behind, has none.
  @Test
  fun aDiscSetOffersNoUpload() = runComposeUiTest {
    val cue = File(storage, "ROMs/gb/Disc.cue").apply { writeText("FILE \"Disc.bin\" BINARY\n") }
    File(storage, "ROMs/gb/Disc.bin").writeBytes(ByteArray(8))
    launch(open = OpenTarget.Local(cue.path))
    awaitText("Play")
    press(Key.ButtonX)
    awaitTag("menu:remove")
    onNodeWithTag("menu:upload").assertDoesNotExist()
  }

  @Test
  fun removingALocalGameDeletesItsFileAfterAsking() = runComposeUiTest {
    openLibrary()
    assertFocused("library:platform:1")
    press(listOf(Key.ButtonA))
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Play")
    press(Key.ButtonX)
    assertFocused("menu:remove")
    press(Key.ButtonA)
    awaitText("Can't download it again")
    // Cancel has focus first.
    assertFocused("remove:cancel")
    press(Key.ButtonA)
    assertTrue(homebrew.exists())
    press(listOf(Key.ButtonX, Key.ButtonA, Key.DirectionRight, Key.ButtonA))
    // The game is gone, and so is its page.
    awaitTag("shelf:platform:1")
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(homebrewTag)).fetchSemanticsNodes().isEmpty()
    }
    assertFalse(homebrew.exists())
    assertTrue(File(storage, "ROMs/gb/Homebrew.srm").exists())
    assertTrue(demo.exists())
  }

  @Test
  fun aLocalGameBecomesTheServersGameOnceTheServerHasIt() = runComposeUiTest {
    val data = openHomebrew()
    onNodeWithTag("game:saves-local").assertExists()
    serverHasHomebrew = true
    data.romm.syncNow()
    // The page is the server's game's now, which has its saves on RomM.
    awaitText("No saves on RomM")
    onNodeWithTag("game:saves-local").assertDoesNotExist()
    onNodeWithText("Homebrew (World)").assertExists()
    onNodeWithTag("primary").assertIsFocused()
  }

  // A choice made while the file became the server's game is that game's.
  @Test
  fun aChoiceMadeAsTheFileBecomesAServerGameIsThatGames() = runComposeUiTest {
    val data = openHomebrew()
    awaitText("Opens in Built-in · Gambatte")
    press(Key.ButtonX)
    awaitTag("menu:open-with")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitText("Kept for this file")
    serverHasHomebrew = true
    data.romm.syncNow()
    // The screen is the server's game's before the choice.
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText("Chosen for this game")).fetchSemanticsNodes().isNotEmpty() &&
        onAllNodes(hasText("Kept for this file", substring = true)).fetchSemanticsNodes().isEmpty()
    }
    waitForIdle()
    // Built-in is first.
    press(Key.ButtonA)
    awaitText("Homebrew (World)")
    val games = { data.settings.settings.value.launch.gameOverrides }
    waitUntil(timeoutMillis = 5_000) { games().isNotEmpty() }
    assertEquals(setOf<GameKey>(GameKey.Romm(2)), games().keys)
  }

  @Test
  fun uploadingALocalGameMakesItTheServersGame() = runComposeUiTest {
    openHomebrew()
    press(Key.ButtonX)
    assertFocused("menu:remove")
    press(listOf(Key.DirectionDown, Key.ButtonA))
    awaitText("Upload Homebrew to RomM?")
    // Cancel has focus first, so nothing goes to the server by accident.
    assertFocused("upload:cancel")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    // RomM scans the game in, the library syncs, and the page is the server's game's.
    awaitText("Homebrew (World)")
    onNodeWithTag("game:saves-local").assertDoesNotExist()
    assertEquals(
      listOf(
        "roms/upload/start",
        "roms/upload/u1",
        "roms/upload/u1/complete",
        "tasks/scan",
        "tasks/t1",
      ),
      uploadRequests.toList(),
    )
    assertTrue(homebrew.exists())
  }

  @Test
  fun anUploadGoesOnWhenItsPageCloses() = runComposeUiTest {
    val hold = CompletableDeferred<Unit>()
    uploadHold = hold
    openLibrary()
    assertFocused("library:platform:1")
    press(Key.ButtonA)
    assertFocused("game:1")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Play")
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitTag("upload:confirm")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Uploading")
    press(Key.ButtonB)
    assertFocused(homebrewTag)
    press(Key.ButtonA)
    awaitText("Uploading")
    onNodeWithTag("primary").assertTextEquals("Uploading")
    hold.complete(Unit)
    awaitText("Homebrew (World)")
  }

  @Test
  fun aRefusedUploadSaysWhyAndOffersToSignInAgain() = runComposeUiTest {
    refuseUploads = true
    openHomebrew()
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitTag("upload:confirm")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    // A pasted token's permissions aren't known, so its sign-in may be why.
    awaitText("Sign in again to allow uploads?")
    assertFocused("allowUploads:cancel")
    press(Key.ButtonA)
    waitForIdle()
    onNodeWithTag("allowUploads:cancel").assertDoesNotExist()
    onNodeWithText("Upload refused · No upload permission").assertExists()
    assertEquals(listOf("roms/upload/start"), uploadRequests.toList())
    onNodeWithTag("primary").assertTextEquals("Play")
    onNodeWithTag("game:saves-local").assertExists()
    assertTrue(credentials.read() != null)
  }

  @Test
  fun aRefusalDespitePermissionToUploadIsTheAccounts() = runComposeUiTest {
    refuseUploads = true
    credentials.write(
      RommCredentials(TestServer, "rmm_token", 1, "player", "d1", listOf("roms.write")),
    )
    openHomebrew()
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitTag("upload:confirm")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Upload refused · No upload permission")
    onNodeWithTag("allowUploads:cancel").assertDoesNotExist()
  }

  @Test
  fun aSignInWithoutUploadsOffersToSignInAgainWithThem() = runComposeUiTest {
    accountScopes = """["roms.read", "roms.write", "tasks.run"]"""
    credentials.write(
      RommCredentials(TestServer, "rmm_token", 1, "player", "d1", listOf("roms.read")),
    )
    openHomebrew()
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    // It asks before anything goes to the server.
    awaitText("Sign in again to allow uploads?")
    onNodeWithText("Uploads not allowed by this sign-in").assertExists()
    onNodeWithTag("upload:confirm").assertDoesNotExist()
    assertFocused("allowUploads:cancel")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    // The sign-in, on the same server, asking for permission to upload this time.
    assertFocused("address")
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: $TestServer")
    onNodeWithTag("allowUploads")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    assertEquals(null, credentials.read())
    assertEquals(emptyList(), uploadRequests.toList())
    assertTrue(homebrew.exists())
  }

  @Test
  fun anAccountThatMayNotUploadIsToldSo() = runComposeUiTest {
    accountScopes = """["roms.read"]"""
    credentials.write(
      RommCredentials(TestServer, "rmm_token", 1, "player", "d1", listOf("roms.read")),
    )
    openHomebrew()
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitText("Uploads not allowed for your RomM account")
    onNodeWithTag("allowUploads:cancel").assertDoesNotExist()
    onNodeWithTag("upload:confirm").assertDoesNotExist()
    assertEquals(emptyList(), uploadRequests.toList())
  }

  private companion object {
    val HomebrewRom =
      rom(2, "Homebrew (World)", file = "Homebrew.gb", size = 8, added = "2024-01-02")
  }
}
