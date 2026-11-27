package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.ui.cover.coverImageLoader
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.focus.LocalFocusScopes
import dev.sargunv.handheld.ui.focus.isDialogOpen
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
 * The app's root, shared by every platform. It opens on sign-in until [data]'s RomM session is
 * signed in to a server, and on Home after that. Start opens the main menu on every screen, sign-in
 * included.
 *
 * The screens show what the library cache holds while the session syncs it, so they work offline.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param data the games, the RomM sign-in and the download queue, which outlive the app's window.
 * @param platform what the platform gives the app besides its data.
 * @param coverCacheDir where covers are kept once fetched.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 * @param openRequests the screens to show, for development tools, each once signed in. Before
 *   sign-in, a Settings one shows over the sign-in.
 * @param homePresses the presses of the system's HOME while the app is in front as the home app.
 *   Each closes the open dialogs, or when there are none, starts over on Home. A game under way
 *   keeps its screen, since leaving it would stop the play and its sync.
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  data: HandheldData,
  platform: AppPlatform,
  coverCacheDir: Path,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
  openRequests: Flow<OpenTarget> = emptyFlow(),
  homePresses: Flow<Unit> = emptyFlow(),
) {
  setSingletonImageLoaderFactory { context -> coverImageLoader(context, coverCacheDir) }
  val scope = rememberCoroutineScope()
  val state = remember(data, platform) { AppState(initialSettings, data, platform, scope) }
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
      val signedIn = data.romm.state.mapNotNull { it.signedIn }
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
            SettingsSecondary(state, signedIn = false)
          }
        } else {
          SignInSecondary(account.signIn)
        }
      true -> MainSecondary(state)
    }
  }
  ActionInputRoot(state.settings.input, heldKeys, modifier) {
    FocusRoot {
      val scopes = LocalFocusScopes.current
      LaunchedEffect(homePresses, scopes, state) {
        homePresses.collect {
          when {
            state.playing != null -> Unit
            scopes?.isDialogOpen == true -> scopes.closeDialogs()
            data.romm.state.value.signedIn == true -> state.goHome()
            data.romm.state.value.signedIn == false -> account.settingsOverSignIn = false
          }
        }
      }
      DualScreen(layout = layout, primary = primary, secondary = secondary)
    }
  }
  if (layout == DualScreenLayout.Separate) secondaryDisplay(secondary)
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
