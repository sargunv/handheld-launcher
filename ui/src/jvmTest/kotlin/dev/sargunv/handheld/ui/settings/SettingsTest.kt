package dev.sargunv.handheld.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.SavedChoice
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SettingsTest {
  private val credentials = InMemoryCredentialStore(RommCredentials(Server, "rmm_token"))
  private val saved = mutableListOf<Settings>()

  /** The signed-in account, as the server reports it. */
  @Volatile private var user = User

  /** How many syncs reached the server. */
  private val syncs = AtomicInteger()

  /**
   * A server with one game on the Game Boy, signed in as `player`. When [refuses], it refuses the
   * token, as after it was revoked.
   */
  private fun server(refuses: Boolean = false) = {
    MockEngine { request ->
      if (refuses) return@MockEngine respondError(HttpStatusCode.Unauthorized)
      val body =
        when (request.url.encodedPath.substringAfter("/api/")) {
          "platforms" -> {
            syncs.incrementAndGet()
            "[$Platform]"
          }
          "roms" -> page(listOf(Rom1))
          "roms/identifiers" -> "[1]"
          "collections",
          "collections/smart" -> "[]"
          "users/me" -> user
          else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  }

  private fun ComposeUiTest.launch(
    open: OpenTarget? = null,
    refuses: Boolean = false,
    store: CredentialStore = credentials,
    apps: AppCatalog = AppCatalog.None,
    storage: List<StorageTarget> = emptyList(),
    onDownloads: (LibraryCache, DownloadManager) -> Unit = { _, _ -> },
  ) {
    val engine = server(refuses)
    val request = open?.let(::OpenRequest)
    setContent {
      val heldKeys = remember { HeldKeys() }
      val library = remember { LibraryCache.open(path = null) }
      val downloads = rememberTestDownloads(library, store, storage)
      remember { onDownloads(library, downloads) }
      App(
        DualScreenLayout.Frame,
        heldKeys,
        store,
        TestDevice,
        library,
        downloads,
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        saveSettings = { saved += it },
        httpEngine = engine,
        apps = apps,
        openRequest = request,
      )
    }
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

  private fun ComposeUiTest.awaitFocused(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
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

  /** Moves focus down the section's settings until [tag] has it. */
  private fun ComposeUiTest.focusDownTo(tag: String) {
    repeat(10) {
      if (onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
      press(Key.DirectionDown)
    }
    onNodeWithTag(tag).assertIsFocused()
  }

  @Test
  fun signingOutAsksFirstAndOffersTheSameServerAgain() = runComposeUiTest {
    launch(OpenTarget.Settings(SettingsSection.RomM))
    awaitFocused("settings:RomM")
    awaitText("player")
    onNodeWithTag("settings:romm:server").assertTextEquals("Server", Server)
    onNodeWithTag("settings:romm:account").assertTextEquals("Account", "player")
    press(Key.DirectionRight)
    focusDownTo("settings:romm:signOut")
    // The lower screen describes the focused setting.
    onNodeWithTag("settings:help:title").assertTextEquals("Sign out")

    // Cancel has focus first, so one press too many signs nothing out.
    press(Key.ButtonA)
    awaitTag("signOut:cancel")
    onNodeWithTag("signOut:cancel").assertIsFocused()
    press(Key.ButtonA)
    onNodeWithTag("settings:romm:signOut").assertIsFocused()
    assertNotNull(runBlocking { credentials.load() })

    press(listOf(Key.ButtonA, Key.DirectionUp, Key.ButtonA))
    // The sign-in itself, not the request for Settings shown again over it.
    awaitFocused("address")
    onNodeWithTag("settings:Controls").assertDoesNotExist()
    assertNull(runBlocking { credentials.load() })
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: $Server")
  }

  // A queued or failed game is the old server's, so it mustn't run against the next one.
  @Test
  fun signingOutEmptiesTheDownloadQueue() = runComposeUiTest {
    var downloads: DownloadManager? = null
    var library: LibraryCache? = null
    launch(OpenTarget.Settings(SettingsSection.RomM)) { l, d ->
      library = l
      downloads = d
    }
    awaitText("player")
    waitUntil(timeoutMillis = 5_000) { runBlocking { library!!.rom(1) } != null }
    runBlocking { downloads!!.enqueue(assertNotNull(library!!.rom(1))) }
    assertEquals(1, downloads!!.queue.value.size)

    press(Key.DirectionRight)
    focusDownTo("settings:romm:signOut")
    press(Key.ButtonA)
    awaitTag("signOut:cancel")
    press(listOf(Key.DirectionUp, Key.ButtonA))
    awaitFocused("address")
    assertEquals(emptyList(), downloads!!.queue.value)
  }

  // Accessibility services move their own focus, so a section also opens when they activate it.
  @Test
  fun aSectionOpensWhenActivated() = runComposeUiTest {
    launch(OpenTarget.Settings(SettingsSection.Controls))
    awaitFocused("settings:Controls")
    onNodeWithTag("settings:Emulators").performSemanticsAction(SemanticsActions.OnClick)
    awaitTag("settings:emulators:gb")
  }

  @Test
  fun aRefusedSignInSaysSoAndSignsInAgain() = runComposeUiTest {
    launch(refuses = true)
    awaitText("RomM refused the sign-in · Settings → RomM")
    onNodeWithTag("sync:status").assertTextEquals("RomM refused the sign-in · Settings → RomM")

    press(Key.ButtonStart)
    awaitTag("main:Settings")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    awaitTag("settings:Controls")
    press(listOf(Key.DirectionDown, Key.DirectionRight))
    awaitTag("settings:romm:refused")
    onNodeWithTag("settings:romm:sync").assertTextEquals("Library", "RomM refused the sign-in")
    focusDownTo("settings:romm:signInAgain")
    // The token is no good, so it signs in again without asking.
    press(Key.ButtonA)
    awaitTag("address")
    assertNull(runBlocking { credentials.load() })
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: $Server")
  }

  @Test
  fun showsWhetherUploadsAreAllowed() = runComposeUiTest {
    // RomM doesn't say what a pasted token may do.
    launch(OpenTarget.Settings(SettingsSection.RomM))
    awaitFocused("settings:RomM")
    awaitText("Up to your API token")
    onNodeWithTag("settings:romm:uploads").assertTextEquals("Uploads", "Up to your API token")
    onNodeWithTag("settings:romm:allowUploads").assertDoesNotExist()
  }

  @Test
  fun showsThatADeviceSignInMayUpload() = runComposeUiTest {
    runBlocking {
      credentials.save(
        RommCredentials(Server, "rmm_token", "d1", listOf("roms.read", "roms.write")),
      )
    }
    launch(OpenTarget.Settings(SettingsSection.RomM))
    awaitText("Allowed")
    onNodeWithTag("settings:romm:uploads").assertTextEquals("Uploads", "Allowed")
    onNodeWithTag("settings:romm:allowUploads").assertDoesNotExist()
  }

  @Test
  fun aSignInWithoutUploadsOffersToSignInAgainWithThem() = runComposeUiTest {
    user =
      User.replace("\"role\": \"user\"", "\"role\": \"user\", \"oauth_scopes\": [\"roms.write\"]")
    runBlocking {
      credentials.save(RommCredentials(Server, "rmm_token", "d1", listOf("roms.read")))
    }
    launch(OpenTarget.Settings(SettingsSection.RomM))
    awaitFocused("settings:RomM")
    awaitText("Not allowed by this sign-in")
    press(Key.DirectionRight)
    focusDownTo("settings:romm:allowUploads")
    // It asks first, as signing out does.
    press(Key.ButtonA)
    awaitFocused("allowUploads:cancel")
    press(Key.ButtonA)
    awaitFocused("settings:romm:allowUploads")
    assertNotNull(runBlocking { credentials.load() })

    press(listOf(Key.ButtonA, Key.DirectionRight, Key.ButtonA))
    awaitFocused("address")
    assertNull(runBlocking { credentials.load() })
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: $Server")
    onNodeWithTag("allowUploads")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
  }

  @Test
  fun syncNowSyncsAgain() = runComposeUiTest {
    launch(OpenTarget.Settings(SettingsSection.RomM))
    awaitFocused("settings:RomM")
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag("settings:romm:sync") and hasText("Synced"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertEquals(1, syncs.get())
    press(Key.DirectionRight)
    focusDownTo("settings:romm:syncNow")
    press(Key.ButtonA)
    waitUntil(timeoutMillis = 5_000) { syncs.get() == 2 }
  }

  @Test
  fun eachPlatformOpensOpenWithForItsGames() = runComposeUiTest {
    val apps = AppCatalog {
      listOf(InstalledApp("com.example.notes", "Notes"), InstalledApp("com.retroarch", "RetroArch"))
    }
    launch(OpenTarget.Settings(SettingsSection.Emulators), apps = apps)
    awaitFocused("settings:Emulators")
    awaitTag("settings:emulators:gb")
    // What the resolver picks for the platform.
    onNodeWithTag("settings:emulators:gb").assertTextEquals("Game Boy", "Built-in · Gambatte")

    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitText("Open Game Boy games with")
    awaitText("Why Game Boy games open in Built-in · Gambatte")
    // A game's own choice doesn't apply to the platform, so the chain starts at the platform.
    onNodeWithText("Platform Game Boy: Built-in (default) · opens here").assertExists()
    onNodeWithTag("openWith:current").assertTextEquals("Nothing chosen")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))

    awaitFocused("settings:emulators:gb")
    onNodeWithTag("settings:emulators:gb").assertTextEquals("Game Boy", "Notes")
    assertEquals(mapOf("gb" to SavedChoice("com.example.notes")), saved.last().launch.platforms)
    assertEquals(emptyMap(), saved.last().launch.games)

    // Clearing the choice goes back to the default.
    press(Key.ButtonA)
    awaitText("Clearing it opens them in Built-in · Gambatte.")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitFocused("settings:emulators:gb")
    onNodeWithTag("settings:emulators:gb").assertTextEquals("Game Boy", "Built-in · Gambatte")
    assertEquals(emptyMap(), saved.last().launch.platforms)
  }

  @Test
  fun beforeSignInTheMainMenuOffersOnlySettings() = runComposeUiTest {
    launch(store = InMemoryCredentialStore())
    awaitTag("address")
    press(Key.ButtonStart)
    awaitTag("main:Settings")
    onNodeWithTag("main:Home").assertDoesNotExist()
    press(Key.ButtonA)
    awaitTag("settings:Controls")
    onNodeWithTag("settings:About").assertExists()
    onNodeWithTag("settings:Emulators").assertDoesNotExist()
    press(listOf(Key.DirectionDown, Key.DirectionRight))
    onNodeWithTag("settings:romm:server").assertTextEquals("Server", "Not signed in")
    // Back returns to the sign-in where it was.
    press(Key.ButtonB)
    onNodeWithTag("settings:Controls").assertDoesNotExist()
    awaitFocused("address")
  }

  @Test
  fun storageTurnsOnAndOffAndMovesFromItsMenu() = runComposeUiTest {
    val own = StorageTarget(tempDir(), "Device", "primary")
    val card = StorageTarget(tempDir(), "SD card", "card")
    launch(OpenTarget.Settings(SettingsSection.Storage), storage = listOf(own, card))
    awaitFocused("settings:Storage")
    awaitTag("settings:storage:card")
    press(Key.DirectionRight)
    awaitFocused("settings:storage:primary")
    onNodeWithTag("settings:storage:primary").assertTextContains("On", substring = true)

    // Confirm turns the device's own storage off, which leaves the card on.
    press(Key.ButtonA)
    onNodeWithTag("settings:storage:primary").assertTextContains("Off", substring = true)
    assertEquals(StorageSettings(off = setOf("primary")), saved.last().storage)

    // The card is the last that's on, so it stays on and a note says why.
    press(Key.DirectionDown)
    awaitFocused("settings:storage:card")
    val before = saved.size
    press(Key.ButtonA)
    awaitTag("settings:storage:refused")
    onNodeWithTag("settings:storage:card").assertTextContains("On", substring = true)
    assertEquals(before, saved.size)

    // Its menu moves it above the device's own storage.
    press(Key.ButtonX)
    onNodeWithTag("menu:down").assertDoesNotExist()
    awaitFocused("menu:up")
    press(Key.ButtonA)
    assertEquals(listOf("card", "primary"), saved.last().storage.order)
    awaitFocused("settings:storage:card")
    press(Key.DirectionDown)
    awaitFocused("settings:storage:primary")
  }

  @Test
  fun theOnlyStorageCantBeTurnedOffOrMoved() = runComposeUiTest {
    val own = StorageTarget(tempDir(), "Device", "primary")
    launch(OpenTarget.Settings(SettingsSection.Storage), storage = listOf(own))
    awaitFocused("settings:Storage")
    awaitTag("settings:storage:primary")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    awaitTag("settings:storage:refused")
    onNodeWithTag("settings:storage:primary").assertTextContains("On", substring = true)
    press(Key.ButtonX)
    awaitFocused("menu:toggle")
    onNodeWithTag("menu:up").assertDoesNotExist()
    onNodeWithTag("menu:down").assertDoesNotExist()
    assertEquals(StorageSettings(), saved.lastOrNull()?.storage ?: StorageSettings())
  }

  @Test
  fun theConfirmButtonSwaps() = runComposeUiTest {
    launch(OpenTarget.Settings(SettingsSection.Controls))
    awaitFocused("settings:Controls")
    press(listOf(Key.DirectionRight, Key.ButtonA))
    onNodeWithTag("settings:confirm").assertTextEquals("Confirm button", "B")
    assertEquals(ConfirmButton.B, saved.last().input.confirmButton)
    // Now B confirms, so it swaps them back.
    press(Key.ButtonB)
    onNodeWithTag("settings:confirm").assertTextEquals("Confirm button", "A")
    assertEquals(ConfirmButton.A, saved.last().input.confirmButton)
  }

  private val tempDirs = mutableListOf<File>()

  /** A new, empty directory, deleted after the test. */
  private fun tempDir() =
    createTempDirectory("handheld-storage").toFile().also { tempDirs += it }.path

  @AfterTest fun cleanUp() = tempDirs.forEach { it.deleteRecursively() }

  private companion object {
    const val Server = "https://romm.test"

    const val User =
      """{"id": 1, "username": "player", "role": "user",
      "created_at": "2024-01-01T00:00:00+00:00", "updated_at": "2024-01-01T00:00:00+00:00"}"""

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

    fun page(items: List<String>) =
      """{"items": [${items.joinToString()}], "total": ${items.size}, "limit": 200, "offset": 0}"""
  }
}
