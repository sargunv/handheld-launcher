package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.setSingletonImageLoaderFactory
import com.composeunstyled.Text
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.LibrarySync
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.platformHttpEngine
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.cover.coverImageLoader
import dev.sargunv.handheld.ui.dialog.Dialogs
import dev.sargunv.handheld.ui.dialog.LocalDialogs
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.focus.LocalFocusScopes
import dev.sargunv.handheld.ui.focus.isDialogOpen
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.library.deviceStates
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.HomePress
import dev.sargunv.handheld.ui.places.MainMenu
import dev.sargunv.handheld.ui.places.MainPrimary
import dev.sargunv.handheld.ui.places.MainSecondary
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.settings.SettingsPage
import dev.sargunv.handheld.ui.settings.SettingsSecondary
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * The app's root, shared by every platform. It opens on sign-in until [credentials] holds a server,
 * and on Home after that. Start opens the main menu on every screen, sign-in included.
 *
 * Once signed in, it syncs [library] with the server when it starts and after each sign-in. The
 * screens show what [library] holds in the meantime, so they work offline.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param library the library cache, which the app keeps open for as long as it runs.
 * @param downloads the download queue. While signed in, the app takes the games sent to this device
 *   from RomM's web page into it when it starts and every minute after.
 * @param games the games on the device's storage.
 * @param coverCacheDir where covers are kept once fetched.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param device this device, as it introduces itself to the server when it signs in.
 * @param httpEngine makes the HTTP engine for each of the server's clients.
 * @param launcher plays games, on platforms that can.
 * @param retroAchievements the device's RetroAchievements account, on platforms whose games can
 *   earn achievements.
 * @param apps the apps on the device that the player can pick to open games in.
 * @param romHeaders reads what a game's file says about itself, for the Game screen.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 * @param openRequest a screen to show once signed in, for development tools. Before sign-in, only
 *   Settings shows.
 * @param homePress the system's HOME, pressed while the app is in front as the home app. It closes
 *   the open dialogs, or when there are none, starts over on Home.
 * @param appInfo what About says about this build: its version and the components it's built from.
 * @param gameState what the platform keeps per game besides the settings, such as the built-in
 *   session's saves, which goes to a game's ROM with its settings once its file becomes one.
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  credentials: CredentialStore,
  device: DeviceDescription,
  library: LibraryCache,
  downloads: DownloadManager,
  games: DeviceGames,
  coverCacheDir: Path,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  httpEngine: () -> HttpClientEngine = ::platformHttpEngine,
  launcher: GameLauncher = GameLauncher.Unsupported,
  retroAchievements: RetroAchievementsSignIn? = null,
  apps: AppCatalog = AppCatalog.None,
  romHeaders: RomHeaders = RomHeaders.None,
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
  openRequest: OpenRequest? = null,
  homePress: HomePress? = null,
  appInfo: AppInfo = AppInfo.Unknown,
  gameState: PerGameState = PerGameState.None,
) {
  setSingletonImageLoaderFactory { context -> coverImageLoader(context, coverCacheDir) }
  val state =
    remember(
      library,
      downloads,
      games,
      launcher,
      apps,
      credentials,
      httpEngine,
      device,
      retroAchievements,
      appInfo,
      romHeaders,
      gameState,
    ) {
      AppState(
        initialSettings,
        library,
        downloads,
        games,
        launcher,
        apps,
        credentials,
        httpEngine,
        device,
        retroAchievements?.let(::RetroAchievementsState),
        appInfo,
        romHeaders,
        gameState,
      )
    }
  LaunchedEffect(state) { state.account.load() }
  LaunchedEffect(state) { state.retroAchievements?.load() }
  LaunchedEffect(state) {
    downloads.statuses().collect { statuses ->
      state.deviceStates = deviceStates(statuses)
      state.onDevice = buildMap {
        // Of several games on one file, the lowest id, as the file's page picks.
        for ((id, status) in statuses) {
          if (status is RomStatus.OnDevice) merge(status.path, id, ::minOf)
        }
      }
    }
  }
  // A game on the device whose file was a local game takes what was kept for it then.
  LaunchedEffect(state) {
    snapshotFlow { state.onDevice to state.playing }
      .collect { (onDevice, playing) ->
        state.gameState.adopt(
          onDevice.mapNotNull { (path, id) ->
            Adoption(GameKey.Local(path), GameKey.Romm(id)).takeIf {
              playing != it.local && playing != it.rom
            }
          },
        )
      }
  }
  LaunchedEffect(state) { games.localGames().collect { state.localGames = it } }
  val currentSaveSettings by rememberUpdatedState(saveSettings)
  LaunchedEffect(state) {
    snapshotFlow { state.settings }.drop(1).collect { currentSaveSettings(it) }
  }
  val account = state.account
  val signedIn = account.signedIn
  LaunchedEffect(signedIn, state) {
    if (signedIn != true) return@LaunchedEffect
    // The downloads a sign-out kept wait for this sign-in, and leave the queue if they're another
    // server's games.
    downloads.signedIn(credentials.load()?.baseUrl)
    // A new request starts the sync over.
    state.syncRequests.collectLatest {
      state.sync = if (library.romCount() == 0L) SyncStatus.FirstSync else SyncStatus.Syncing
      state.sync =
        try {
          // Reading the server's pages is work too, so it stays off the main thread.
          withContext(Dispatchers.Default) {
            state.romm.client()?.use { LibrarySync(it, library).sync() }
          }
          SyncStatus.Synced
        } catch (_: RommException.Unauthorized) {
          SyncStatus.Unauthorized
        } catch (_: RommException) {
          SyncStatus.Failed
        }
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
  // Each request shows once, so a sign-in or sign-out after it doesn't show it again.
  var shownRequest by remember { mutableStateOf<OpenRequest?>(null) }
  LaunchedEffect(openRequest, signedIn, state) {
    if (openRequest == null || openRequest === shownRequest) return@LaunchedEffect
    val target = openRequest.target
    when (signedIn) {
      true -> {
        shownRequest = openRequest
        state.open(target)
      }
      // Before sign-in, only Settings can show; anything else waits for the sign-in.
      false ->
        if (target is OpenTarget.Settings) {
          shownRequest = openRequest
          account.settingsOverSignIn = true
          target.section?.let(state.settingsView::jumpTo)
        }
      null -> Unit
    }
  }
  var mainMenuOpen by rememberSaveable { mutableStateOf(false) }
  val primary: @Composable () -> Unit = {
    Box(
      Modifier.fillMaxSize().onAction { action ->
        if (action == Action.MainMenu && signedIn != null) {
          mainMenuOpen = true
          true
        } else {
          false
        }
      },
    ) {
      when (signedIn) {
        null -> Box(Modifier.fillMaxSize().background(Placeholder.background))
        false -> {
          SignInPrimary(account.signIn)
          if (account.settingsOverSignIn) SignInSettings(state)
        }
        true -> MainPrimary(state)
      }
      if (mainMenuOpen) MainMenu(state, onDismissRequest = { mainMenuOpen = false })
    }
  }
  val secondary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.secondaryBackground))
      false ->
        if (account.settingsOverSignIn) {
          Box(
            Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(24.dp),
          ) {
            SettingsSecondary(state.settingsView)
          }
        } else {
          SignInSecondary(account.signIn)
        }
      true -> MainSecondary(state)
    }
  }
  val dialogs = remember { Dialogs() }
  CompositionLocalProvider(LocalDialogs provides dialogs) {
    ActionInputRoot(state.inputSettings, heldKeys, modifier) {
      FocusRoot {
        val scopes = LocalFocusScopes.current
        LaunchedEffect(homePress) {
          if (homePress == null) return@LaunchedEffect
          when {
            scopes?.isDialogOpen == true -> dialogs.closeAll()
            signedIn == true -> state.goHome()
            signedIn == false -> account.settingsOverSignIn = false
          }
        }
        DualScreen(layout = layout, primary = primary, secondary = secondary)
      }
    }
    if (layout == DualScreenLayout.Separate) secondaryDisplay(secondary)
  }
}

/**
 * Settings over the sign-in, with only what needs no server. Back returns to the sign-in where it
 * was.
 */
@Composable
private fun SignInSettings(state: AppState) {
  BackHandler(
    onBack = { state.account.settingsOverSignIn = false },
    Modifier.fillMaxSize()
      .background(Placeholder.background)
      // The sign-in behind can't be tapped.
      .pointerInput(Unit) { detectTapGestures {} },
  ) {
    Column(Modifier.fillMaxSize()) {
      Text(
        Place.Settings.title,
        color = Placeholder.text,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
          Modifier.padding(horizontal = 48.dp, vertical = 16.dp)
            .testTag("place:${Place.Settings.name}")
            .semantics { heading() },
      )
      Box(Modifier.fillMaxWidth().weight(1f)) { SettingsPage(state, backHint = "Back") }
    }
  }
}
