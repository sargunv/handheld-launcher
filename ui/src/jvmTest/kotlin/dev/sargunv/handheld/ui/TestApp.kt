@file:OptIn(ExperimentalTestApi::class)

package dev.sargunv.handheld.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.places.HomePress
import dev.sargunv.handheld.ui.places.OpenRequest
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.io.path.createTempDirectory
import kotlinx.io.files.Path

/** The server the tests sign in to. */
internal const val TestServer = "https://romm.test"

/**
 * Shows the app, signed in to [TestServer] unless [credentials] says otherwise, with covers and the
 * download queue in new, empty directories. The app reads [homePress] as it composes, so a test
 * presses HOME by changing the state it reads.
 *
 * @return the app's download queue, which downloads from a server that has nothing.
 */
internal fun ComposeUiTest.launchApp(
  server: () -> HttpClientEngine = fakeRomm { _, _ -> respondError(HttpStatusCode.NotFound) },
  credentials: CredentialStore = InMemoryCredentialStore(RommCredentials(TestServer, "rmm_token")),
  library: LibraryCache = LibraryCache.open(path = null),
  storage: List<StorageTarget> = emptyList(),
  settings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  launcher: GameLauncher = GameLauncher.Unsupported,
  apps: AppCatalog = AppCatalog.None,
  romHeaders: RomHeaders = RomHeaders.None,
  appInfo: AppInfo = AppInfo.Unknown,
  retroAchievements: RetroAchievementsSignIn? = null,
  gameState: PerGameState = PerGameState.None,
  open: OpenRequest? = null,
  homePress: () -> HomePress? = { null },
  layout: DualScreenLayout = DualScreenLayout.Frame,
): DownloadManager {
  lateinit var downloads: DownloadManager
  setContent {
    val heldKeys = remember { HeldKeys() }
    val scope = rememberCoroutineScope()
    val dataDir = remember { createTempDirectory("handheld-data").toString() }
    val games = remember {
      DeviceGames(library, { storage }, scope, Path(dataDir, "local-content.json"))
    }
    downloads = remember {
      DownloadManager(
        queueFile = Path(dataDir, "downloads.json"),
        library = library,
        games = games,
        credentials = credentials,
        storage = { storage },
        scope = scope,
        engine = { MockEngine { respondError(HttpStatusCode.NotFound) } },
      )
    }
    App(
      layout,
      heldKeys,
      credentials,
      DeviceDescription("install-1", "Test device", "test", null),
      library,
      downloads,
      games,
      remember { Path(createTempDirectory("handheld-covers").toString()) },
      Modifier.trackHeldKeys(heldKeys),
      initialSettings = settings,
      saveSettings = saveSettings,
      httpEngine = server,
      launcher = launcher,
      apps = apps,
      romHeaders = romHeaders,
      openRequest = open,
      homePress = homePress(),
      appInfo = appInfo,
      retroAchievements = retroAchievements,
      gameState = gameState,
    )
  }
  return downloads
}

/** Presses each key in turn. */
internal fun ComposeUiTest.press(keys: List<Key>) {
  for (key in keys) {
    // The key goes up once the screen settles: the test tracks held keys through the focused
    // element, and a screen change can leave nothing focused for a moment.
    onRoot().performKeyInput { keyDown(key) }
    waitForIdle()
    onRoot().performKeyInput { keyUp(key) }
    waitForIdle()
  }
}

internal fun ComposeUiTest.press(key: Key) = press(listOf(key))

internal fun ComposeUiTest.awaitTag(tag: String) {
  waitUntil(timeoutMillis = 5_000) {
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
  }
  waitForIdle()
}

/** Waits for an element that shows [text], or a part of it. */
internal fun ComposeUiTest.awaitText(text: String) {
  waitUntil(timeoutMillis = 5_000) {
    onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
  }
  waitForIdle()
}

internal fun ComposeUiTest.awaitFocused(tag: String) {
  waitUntil(timeoutMillis = 5_000) {
    onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
  }
}

/** Waits for [tag] to show, and checks that it has focus. */
internal fun ComposeUiTest.assertFocused(tag: String) {
  awaitTag(tag)
  onNodeWithTag(tag).assertIsFocused()
}
