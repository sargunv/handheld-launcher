package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.LibrarySync
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.platformHttpEngine
import dev.sargunv.handheld.ui.cover.coverImageLoader
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.library.deviceStates
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MainPrimary
import dev.sargunv.handheld.ui.places.MainSecondary
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import dev.sargunv.handheld.ui.signin.SignInState
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * The app's root, shared by every platform. It opens on sign-in until [credentials] holds a server,
 * and on Home after that.
 *
 * Once signed in, it syncs [library] with the server when it starts and after each sign-in. The
 * screens show what [library] holds in the meantime, so they work offline.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param library the library cache, which the app keeps open for as long as it runs.
 * @param downloads the download queue. While signed in, the app takes the games sent to this device
 *   from RomM's web page into it when it starts and every minute after.
 * @param coverCacheDir where covers are kept once fetched.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param device this device, as it introduces itself to the server when it signs in.
 * @param httpEngine makes the HTTP engine for each of the server's clients.
 * @param launcher plays games, on platforms that can.
 * @param apps the apps on the device that the player can pick to open games in.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 * @param openRequest a screen to show once signed in, for development tools.
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  credentials: CredentialStore,
  device: DeviceDescription,
  library: LibraryCache,
  downloads: DownloadManager,
  coverCacheDir: Path,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  httpEngine: () -> HttpClientEngine = ::platformHttpEngine,
  launcher: GameLauncher = GameLauncher.Unsupported,
  apps: AppCatalog = AppCatalog.None,
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
  openRequest: OpenRequest? = null,
) {
  setSingletonImageLoaderFactory { context -> coverImageLoader(context, coverCacheDir) }
  // Null until the stored credentials have been read.
  var signedIn by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(credentials) { signedIn = credentials.load() != null }
  val state =
    remember(library, downloads, launcher, apps, credentials, httpEngine) {
      AppState(initialSettings, library, downloads, launcher, apps, credentials, httpEngine)
    }
  LaunchedEffect(state) {
    downloads.statuses().collect { state.deviceStates = deviceStates(it) }
  }
  val currentSaveSettings by rememberUpdatedState(saveSettings)
  LaunchedEffect(state) {
    snapshotFlow { state.settings }.drop(1).collect { currentSaveSettings(it) }
  }
  val signInApi = remember(credentials, httpEngine) { RommSignIn(credentials, httpEngine) }
  val signIn =
    remember(signInApi, device) { SignInState(signInApi, device, onSignedIn = { signedIn = true }) }
  LaunchedEffect(signedIn, state) {
    if (signedIn != true) return@LaunchedEffect
    state.sync = if (library.romCount() == 0L) SyncStatus.FirstSync else SyncStatus.Syncing
    state.sync =
      try {
        // Reading the server's pages is work too, so it stays off the main thread.
        withContext(Dispatchers.Default) {
          signInApi.client()?.use { LibrarySync(it, library).sync() }
        }
        SyncStatus.Synced
      } catch (_: RommException) {
        SyncStatus.Failed
      }
  }
  LaunchedEffect(signedIn, downloads) {
    if (signedIn != true) return@LaunchedEffect
    while (true) {
      try {
        downloads.takeInstallRequests()
      } catch (_: RommException) {
        // The server is out of reach; the next poll tries again.
      }
      delay(1.minutes)
    }
  }
  LaunchedEffect(openRequest, signedIn, state) {
    if (signedIn == true && openRequest != null) state.open(openRequest.target)
  }
  val primary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.background))
      false -> SignInPrimary(signIn)
      true -> MainPrimary(state)
    }
  }
  val secondary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.secondaryBackground))
      false -> SignInSecondary(signIn)
      true -> MainSecondary(state)
    }
  }
  ActionInputRoot(state.inputSettings, heldKeys, modifier) {
    FocusRoot { DualScreen(layout = layout, primary = primary, secondary = secondary) }
  }
  if (layout == DualScreenLayout.Separate) secondaryDisplay(secondary)
}
