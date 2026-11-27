package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.platformOpensIn
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.signin.Help

@Composable
internal fun rememberEmulatorsSection(state: AppState): EmulatorsSection {
  val explainCloseEmulators = rememberSaveable { mutableStateOf(false) }
  return remember(state) { EmulatorsSection(state, explainCloseEmulators) }
}

internal class EmulatorsSection(val state: AppState, explainCloseEmulators: MutableState<Boolean>) {
  /** Whether the dialog that explains closing emulators shows, before it turns on. */
  var explainCloseEmulators by explainCloseEmulators

  @Composable
  fun Dialogs() {
    if (explainCloseEmulators) {
      CloseEmulatorsDialog(
        onTurnOn = {
          state.settings = state.settings.copy(closeEmulators = true)
          explainCloseEmulators = false
        },
        onDismissRequest = { explainCloseEmulators = false },
      )
    }
  }
}

internal fun LazyListScope.emulators(section: EmulatorsSection) {
  sectionHeading("Emulators")
  val state = section.state
  val view = state.settingsView
  // Only on devices with a root service the launcher can close emulators with.
  if (state.launcher.canCloseEmulators) {
    item(key = "closeEmulators") {
      val on = state.settings.closeEmulators
      SettingRow(
        view,
        "settings:closeEmulators",
        "Close emulators",
        if (on) "On" else "Off",
        Help(
          "Close emulators",
          "Checks whether an emulator is still running before a game starts, and offers to " +
            "close it, so a newer save from RomM can't be overwritten.",
        ),
      ) {
        if (on) state.settings = state.settings.copy(closeEmulators = false)
        else section.explainCloseEmulators = true
      }
    }
  }
  item(key = "platforms") {
    Text(
      "Each platform's games open in",
      color = Placeholder.mutedText,
      modifier = Modifier.semantics { heading() },
    )
  }
  item(key = "platformRows") { PlatformRows(state) }
}

/** Every platform in the library, with the app its games open in when nothing else chooses. */
@Composable
private fun PlatformRows(state: AppState) {
  val platforms by remember(state.library) { state.library.platforms() }.collectAsState(null)
  val apps by produceState<List<InstalledApp>?>(null, state.apps) { value = state.apps.apps() }
  val loaded = platforms ?: return
  val installed = apps ?: return
  if (loaded.isEmpty()) {
    Text("Your library has no platforms yet.", color = Placeholder.mutedText)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (platform in loaded) {
      val slug = PlatformSlug(platform.slug)
      val opensIn = platformOpensIn(slug, state.settings.launch, installed)
      val chosen = state.settings.launch.platforms.containsKey(platform.slug)
      SettingRow(
        state.settingsView,
        "settings:emulators:${platform.slug}",
        platform.name,
        opensIn ?: "No app installed",
        Help(
          platform.name,
          (opensIn?.let { "Its games open in $it" } ?: "No app on this device can open its games") +
            (if (chosen) ", which you chose." else ", by default.") +
            " Choose another app for all its games. A game's or collection's own choice still " +
            "comes first.",
        ),
      ) {
        state.navigator.push(Screen.OpenWith(OpenWithScope.Platform(slug), platform.name))
      }
    }
  }
}

/** Explains closing emulators before the player turns it on. */
@Composable
internal fun CloseEmulatorsDialog(onTurnOn: () -> Unit, onDismissRequest: () -> Unit) {
  Dialog(
    onDismissRequest,
    Modifier.widthIn(max = 640.dp),
    initialFocus = "closeEmulators:cancel",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Close emulators", color = Placeholder.text, modifier = Modifier.semantics { heading() })
      Text(
        "This device has a built-in root service that any app can use. With this on, the " +
          "launcher uses it only to check whether an emulator is still running and to close it, " +
          "so a newer save from RomM can't be overwritten by the emulator's old one.",
        color = Placeholder.mutedText,
      )
      Text(
        "Closing skips the emulator's own exit, so progress since its last in-game save can be " +
          "lost. When you can, quit from the emulator's own menu instead.",
        color = Placeholder.mutedText,
      )
      MenuButton("closeEmulators:turnOn", "Turn on", onConfirm = onTurnOn)
      MenuButton("closeEmulators:cancel", "Cancel", onConfirm = onDismissRequest)
    }
  }
}
