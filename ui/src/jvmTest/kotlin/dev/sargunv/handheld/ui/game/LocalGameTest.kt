package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.LibrarySync
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.library.LibrarySettings
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.StillRunning
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Local games: files in a platform's folder that match none of the server's games. The server has
 * Game 1 on the Game Boy and nothing on the N64; the device has Homebrew.gb and Demo.z64, which
 * match nothing, and a save beside Homebrew.gb, which isn't a game.
 */
@OptIn(ExperimentalTestApi::class)
class LocalGameTest {
  private val storage = createTempDirectory("local-storage").toFile()
  private val homebrew = File(storage, "ROMs/gb/Homebrew.gb")
  private val demo = File(storage, "ROMs/n64/Demo.z64")
  private val library = LibraryCache.open(path = null)

  /** Whether the server has gained Homebrew, under the name of its file on the device. */
  @Volatile private var serverHasHomebrew = false

  /** Whether the server refuses uploads, as for an account that may not upload games. */
  @Volatile private var refuseUploads = false

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
    check(storage.deleteRecursively())
  }

  /** Records what it was asked to play, and plays nothing. */
  private class FakeLauncher : GameLauncher {
    val played = mutableListOf<Pair<LibraryGame, String>>()

    override suspend fun opensIn(
      game: LibraryGame,
      rules: LaunchRules,
      collections: Set<CollectionId>,
    ) = "Opens in Test Emulator"

    override suspend fun play(
      game: LibraryGame,
      path: String,
      rules: LaunchRules,
      collections: Set<CollectionId>,
      onProgress: (String) -> Unit,
      closeEmulators: Boolean,
      askStillRunning: suspend (StillRunning) -> StillRunningChoice,
      chooseSave: suspend (SaveConflict) -> SaveCopy?,
    ): List<String> {
      played += game to path
      return listOf("Played")
    }

    override suspend fun keptSaves(game: LibraryGame) = emptyList<KeptSave>()
  }

  private val server = {
    MockEngine { request ->
      val roms = listOfNotNull(Rom1, HomebrewRom.takeIf { serverHasHomebrew })
      val path = request.url.encodedPath.substringAfter("/api/")
      if (path.startsWith("roms/upload") || path.startsWith("tasks")) uploadRequests += path
      val body =
        when (path) {
          "roms/upload/start" ->
            if (refuseUploads) {
              return@MockEngine respond(
                """{"detail": "Forbidden"}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"),
              )
            } else """{"upload_id": "u1"}"""
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
          "platforms" -> "[$GameBoy, $N64]"
          "roms" ->
            if (request.url.parameters["last_played"] == "true") page(emptyList()) else page(roms)
          "roms/identifiers" -> if (serverHasHomebrew) "[1, 2]" else "[1]"
          "collections",
          "collections/smart" -> "[]"
          "saves" -> "[]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  private fun ComposeUiTest.launch(
    launcher: GameLauncher = FakeLauncher(),
    settings: Settings = Settings(),
    open: OpenRequest? = null,
  ) {
    setContent {
      val heldKeys = remember { HeldKeys() }
      val credentials = remember {
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token"))
      }
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
        initialSettings = settings,
        httpEngine = server,
        launcher = launcher,
        openRequest = open,
      )
    }
  }

  /** Launches, and goes to the Library once the sync has filled it. */
  private fun ComposeUiTest.openLibrary(tab: LibraryTab = LibraryTab.Platforms) {
    launch(settings = Settings(library = LibrarySettings(tab = tab)))
    awaitTag("shelf:RecentlyAdded")
    press(listOf(Key.ButtonStart, Key.DirectionDown, Key.ButtonA))
    awaitTag("place:Library")
  }

  /** Launches, and opens Homebrew's page from the Game Boy's games. */
  private fun ComposeUiTest.openHomebrew(launcher: GameLauncher = FakeLauncher()) {
    launch(launcher, open = OpenRequest(OpenTarget.Local(homebrew.path)))
    awaitText("Play")
    onNodeWithTag("primary").assertIsFocused()
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

  private fun ComposeUiTest.assertFocused(tag: String) {
    awaitTag(tag)
    onNodeWithTag(tag).assertIsFocused()
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      onRoot().performKeyInput { keyDown(key) }
      waitForIdle()
      onRoot().performKeyInput { keyUp(key) }
      waitForIdle()
    }
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
    onNodeWithTag("game:saves-local")
      .assert(hasText("Saves stay on this device until the game is on your server."))
    awaitText("Opens in Test Emulator")

    press(Key.ButtonA)
    awaitText("Played")
    val (game, path) = launcher.played.single()
    assertEquals(homebrew.path, path)
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
    awaitText("keeps this choice for its file")
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
    awaitText("you can't download it again")
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
    openHomebrew()
    onNodeWithTag("game:saves-local").assertExists()
    serverHasHomebrew = true
    runBlocking {
      RommClient("https://romm.test", "rmm_token", server()).use { LibrarySync(it, library).sync() }
    }
    // The page is the server's game's now, which has its saves on RomM.
    awaitText("No saves on RomM yet.")
    onNodeWithTag("game:saves-local").assertDoesNotExist()
    onNodeWithText("Homebrew (World)").assertExists()
    onNodeWithTag("primary").assertIsFocused()
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
  fun aRefusedUploadSaysWhy() = runComposeUiTest {
    refuseUploads = true
    openHomebrew()
    press(listOf(Key.ButtonX, Key.DirectionDown, Key.ButtonA))
    awaitTag("upload:confirm")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText(
      "RomM didn't allow the upload: Forbidden. Your account needs permission to upload games.",
    )
    assertEquals(listOf("roms/upload/start"), uploadRequests.toList())
    onNodeWithTag("primary").assertTextEquals("Play")
    onNodeWithTag("game:saves-local").assertExists()
  }

  private companion object {
    const val GameBoy =
      """{"id": 1, "slug": "gb", "fs_slug": "gb", "name": "Game Boy", "display_name": "Game Boy",
      "rom_count": 1, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    const val N64 =
      """{"id": 2, "slug": "n64", "fs_slug": "n64", "name": "N64", "display_name": "N64",
      "rom_count": 0, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

    const val Rom1 =
      """{"id": 1, "platform_id": 1, "platform_slug": "gb", "platform_fs_slug": "gb",
      "platform_display_name": "Game Boy", "name": "Game 1", "fs_name": "Game 1.gb",
      "fs_name_no_tags": "Game 1", "fs_name_no_ext": "Game 1", "fs_extension": "gb",
      "fs_size_bytes": 1024, "path_cover_small": "", "url_cover": "",
      "created_at": "2024-01-01T00:00:00+00:00", "updated_at": "2024-01-01T00:00:00+00:00"}"""

    const val HomebrewRom =
      """{"id": 2, "platform_id": 1, "platform_slug": "gb", "platform_fs_slug": "gb",
      "platform_display_name": "Game Boy", "name": "Homebrew (World)", "fs_name": "Homebrew.gb",
      "fs_name_no_tags": "Homebrew", "fs_name_no_ext": "Homebrew", "fs_extension": "gb",
      "fs_size_bytes": 8, "path_cover_small": "", "url_cover": "",
      "created_at": "2024-01-02T00:00:00+00:00", "updated_at": "2024-01-02T00:00:00+00:00"}"""

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
