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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.romm.RommSession
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
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MainMenu
import dev.sargunv.handheld.ui.places.MainPrimary
import dev.sargunv.handheld.ui.places.MainSecondary
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.Place
import dev.sargunv.handheld.ui.places.open
import dev.sargunv.handheld.ui.places.signedIn
import dev.sargunv.handheld.ui.settings.SettingsPage
import dev.sargunv.handheld.ui.settings.SettingsSecondary
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.io.files.Path

/**
 * The app's root, shared by every platform. It opens on sign-in until [romm] is signed in to a
 * server, and on Home after that. Start opens the main menu on every screen, sign-in included.
 *
 * The screens show what [library] holds while [romm] syncs it, so they work offline.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param romm the sign-in to the RomM server, which syncs [library] with it.
 * @param library the library cache, which the app keeps open for as long as it runs.
 * @param downloads the download queue.
 * @param games the games on the device's storage.
 * @param coverCacheDir where covers are kept once fetched.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param device this device, as it introduces itself to the server when it signs in.
 * @param launcher plays games, on platforms that can.
 * @param retroAchievements the device's RetroAchievements account, on platforms whose games can
 *   earn achievements.
 * @param apps the apps on the device that the player can pick to open games in.
 * @param romHeaders reads what a game's file says about itself, for the Game screen.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 * @param openRequests the screens to show, for development tools, each once signed in. Before
 *   sign-in, a Settings one shows over the sign-in.
 * @param homePresses the presses of the system's HOME while the app is in front as the home app.
 *   Each closes the open dialogs, or when there are none, starts over on Home.
 * @param appInfo what About says about this build: its version and the components it's built from.
 * @param gameState what the platform keeps per game besides the settings, such as the built-in
 *   session's saves, which goes to a game's ROM with its settings once its file becomes one.
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  romm: RommSession,
  device: DeviceDescription,
  library: LibraryCache,
  downloads: DownloadManager,
  games: DeviceGames,
  coverCacheDir: Path,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  launcher: GameLauncher = GameLauncher.Unsupported,
  retroAchievements: RetroAchievementsSignIn? = null,
  apps: AppCatalog = AppCatalog.None,
  romHeaders: RomHeaders = RomHeaders.None,
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
  openRequests: Flow<OpenTarget> = emptyFlow(),
  homePresses: Flow<Unit> = emptyFlow(),
  appInfo: AppInfo = AppInfo.Unknown,
  gameState: PerGameState = PerGameState.None,
) {
  setSingletonImageLoaderFactory { context -> coverImageLoader(context, coverCacheDir) }
  val scope = rememberCoroutineScope()
  val state =
    remember(
      library,
      downloads,
      games,
      launcher,
      apps,
      romm,
      device,
      retroAchievements,
      appInfo,
      romHeaders,
      gameState,
    ) {
      AppState(
        initialSettings,
        romm,
        library,
        downloads,
        games,
        launcher,
        apps,
        device,
        scope,
        retroAchievements?.let(::RetroAchievementsState),
        appInfo,
        romHeaders,
        gameState,
      )
    }
  LaunchedEffect(state) { state.retroAchievements?.load() }
  LaunchedEffect(state) { state.adoptMatchedFiles() }
  val currentSaveSettings by rememberUpdatedState(saveSettings)
  LaunchedEffect(state) {
    snapshotFlow { state.settings }.drop(1).collect { currentSaveSettings(it) }
  }
  val account = state.account
  val signedIn = account.signedIn
  LaunchedEffect(openRequests, state) {
    openRequests.collectLatest { target ->
      val signedIn = romm.state.mapNotNull { it.signedIn }
      // Before sign-in, only Settings can show; anything else waits for the sign-in.
      if (target is OpenTarget.Settings && !signedIn.first()) {
        account.settingsOverSignIn = true
        target.section?.let(state.settingsView::jumpTo)
      } else {
        signedIn.first { it }
        state.open(target)
      }
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
    ActionInputRoot(state.settings.input, heldKeys, modifier) {
      FocusRoot {
        val scopes = LocalFocusScopes.current
        LaunchedEffect(homePresses, scopes, state) {
          homePresses.collect {
            when {
              scopes?.isDialogOpen == true -> dialogs.closeAll()
              romm.state.value.signedIn == true -> state.goHome()
              romm.state.value.signedIn == false -> account.settingsOverSignIn = false
            }
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
