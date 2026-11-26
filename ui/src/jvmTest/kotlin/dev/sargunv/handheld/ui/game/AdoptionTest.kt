package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.LibrarySync
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.saves.KeptSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * What the app keeps for a local game goes to the server's game once the file is that game's: the
 * device has Homebrew.gb, which the server gains as Homebrew (World).
 */
@OptIn(ExperimentalTestApi::class)
class AdoptionTest {
  private val storage = createTempDirectory("adoption-storage").toFile()
  private val homebrew = File(storage, "ROMs/gb/Homebrew.gb")
  private val library = LibraryCache.open(path = null)
  private val local = GameKey.Local(homebrew.path)
  private val rom = GameKey.Romm(2)
  private val choice = SavedChoice("com.example.emulator")

  @Volatile private var serverHasHomebrew = false

  /** The settings as the app stored them, in order. */
  private val saved = Collections.synchronizedList(mutableListOf<Settings>())

  /** What the platform's per-game state was asked to adopt. */
  private val adopted = Collections.synchronizedList(mutableListOf<Adoption>())

  private lateinit var downloads: DownloadManager

  init {
    homebrew.parentFile.mkdirs()
    homebrew.writeText("homebrew")
  }

  @AfterTest
  fun cleanUp() {
    library.close()
    check(storage.deleteRecursively())
  }

  /** Plays nothing, and returns once [done] completes. */
  private class FakeLauncher : GameLauncher {
    val started = CompletableDeferred<Unit>()
    val done = CompletableDeferred<Unit>()

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
      started.complete(Unit)
      done.await()
      return listOf("Played")
    }

    override suspend fun keptSaves(game: LibraryGame) = emptyList<KeptSave>()
  }

  private val server = {
    MockEngine { request ->
      val roms = listOfNotNull(HomebrewRom.takeIf { serverHasHomebrew })
      val body =
        when (request.url.encodedPath.substringAfter("/api/")) {
          "platforms" -> "[$GameBoy]"
          "roms" ->
            if (request.url.parameters["last_played"] == "true") page(emptyList()) else page(roms)
          "roms/identifiers" -> if (serverHasHomebrew) "[2]" else "[]"
          "collections",
          "collections/smart" -> "[]"
          "saves" -> "[]"
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  /** Opens Homebrew's page, with an Open with choice for its file. */
  private fun ComposeUiTest.openHomebrew(launcher: GameLauncher = FakeLauncher()) {
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
          )
          .also { downloads = it },
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        initialSettings = Settings(launch = LaunchSettings(games = mapOf(local to choice))),
        saveSettings = { saved += it },
        httpEngine = server,
        launcher = launcher,
        openRequest = OpenRequest(OpenTarget.Local(homebrew.path)),
        gameState = PerGameState { adopted += it },
      )
    }
    awaitText("Play")
  }

  private fun ComposeUiTest.awaitText(text: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun serverGainsHomebrew() {
    serverHasHomebrew = true
    runBlocking {
      RommClient("https://romm.test", "rmm_token", server()).use { LibrarySync(it, library).sync() }
      downloads.statuses().first { it[rom.romId] is RomStatus.OnDevice }
    }
  }

  @Test
  fun aLocalGamesStateGoesToTheServersGameInOneStep() = runComposeUiTest {
    openHomebrew()
    serverGainsHomebrew()
    awaitText("Homebrew (World)")
    waitUntil(timeoutMillis = 5_000) { Adoption(local, rom) in adopted }
    waitUntil(timeoutMillis = 5_000) { saved.lastOrNull()?.launch?.games == mapOf(rom to choice) }
  }

  @Test
  fun aGameThatsPlayingKeepsItsStateWhereItIsUntilItsOver() = runComposeUiTest {
    val launcher = FakeLauncher()
    openHomebrew(launcher)
    onRoot().performKeyInput { keyDown(Key.ButtonA) }
    onRoot().performKeyInput { keyUp(Key.ButtonA) }
    runBlocking { launcher.started.await() }
    serverGainsHomebrew()
    waitForIdle()
    Thread.sleep(500)
    waitForIdle()
    assertTrue(adopted.none { it.local == local }, "$adopted")
    assertTrue(saved.none { rom in it.launch.games }, "$saved")

    launcher.done.complete(Unit)
    waitUntil(timeoutMillis = 5_000) { Adoption(local, rom) in adopted }
    waitUntil(timeoutMillis = 5_000) { saved.lastOrNull()?.launch?.games == mapOf(rom to choice) }
    assertEquals(choice, saved.last().launch.games[rom])
  }

  private companion object {
    const val GameBoy =
      """{"id": 1, "slug": "gb", "fs_slug": "gb", "name": "Game Boy", "display_name": "Game Boy",
      "rom_count": 1, "created_at": "2024-01-01T00:00:00+00:00",
      "updated_at": "2024-01-01T00:00:00+00:00"}"""

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
