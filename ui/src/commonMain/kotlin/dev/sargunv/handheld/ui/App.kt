package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
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
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MainMenu
import dev.sargunv.handheld.ui.places.MainPrimary
import dev.sargunv.handheld.ui.places.MainSecondary
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.open
import dev.sargunv.handheld.ui.places.signedIn
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * The app's root, shared by every platform. It opens on sign-in until [data]'s RomM session is
 * signed in to a server, and on Home after that. Start opens the main menu on every screen, sign-in
 * included.
 *
 * The screens show what the library cache holds while the session syncs it, so they work offline.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param data the settings, the games, the RomM sign-in and the download queue, which outlive the
 *   app's window.
 * @param platform what the platform gives the app besides its data.
 * @param coverCacheDir where covers are kept once fetched.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 * @param openRequests the screens to show, for development tools, each once signed in. Before
 *   sign-in, a Settings one shows over the sign-in.
 * @param homePresses the presses of the system's HOME while the app is in front as the home app.
 *   Each closes the open dialogs, or when there are none, starts over on Home, or closes Settings
 *   over the sign-in. A play under way keeps its screen.
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  data: HandheldData,
  platform: AppPlatform,
  coverCacheDir: File,
  modifier: Modifier = Modifier,
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
  openRequests: Flow<OpenTarget> = emptyFlow(),
  homePresses: Flow<Unit> = emptyFlow(),
) {
  setSingletonImageLoaderFactory { context -> coverImageLoader(context, coverCacheDir) }
  val settings = data.settings.settings.collectAsState()
  val state = remember(data, platform) { AppState(data, platform, settings) }
  val signedIn = data.romm.state.collectAsState().value.signedIn
  LaunchedEffect(openRequests, state) {
    openRequests.collectLatest { target ->
      val signedIn = data.romm.state.mapNotNull { it.signedIn }
      // Before sign-in, only Settings can show; anything else waits for the sign-in.
      if (target is OpenTarget.Settings && !signedIn.first()) {
        state.signIn.showSettings()
        target.section?.let(state.settingsView::jumpTo)
      } else {
        signedIn.first { it }
        state.open(target)
      }
    }
  }
  var mainMenuOpen by remember { mutableStateOf(false) }
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
        false -> SignInPrimary(state)
        true -> MainPrimary(state)
      }
      if (mainMenuOpen) MainMenu(state, onDismissRequest = { mainMenuOpen = false })
    }
  }
  val secondary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.secondaryBackground))
      false -> SignInSecondary(state)
      true -> MainSecondary(state)
    }
  }
  ActionInputRoot(state.settings.input, heldKeys, modifier) {
    FocusRoot {
      val scopes = LocalFocusScopes.current
      LaunchedEffect(homePresses, scopes, state) {
        homePresses.collect {
          when {
            state.launcher.playing.value != null -> Unit
            scopes?.isDialogOpen == true -> scopes.closeDialogs()
            data.romm.state.value.signedIn == true -> state.goHome()
            data.romm.state.value.signedIn == false -> state.signIn.closeSettings()
          }
        }
      }
      DualScreen(layout = layout, primary = primary, secondary = secondary)
    }
  }
  if (layout == DualScreenLayout.Separate) secondaryDisplay(secondary)
}
