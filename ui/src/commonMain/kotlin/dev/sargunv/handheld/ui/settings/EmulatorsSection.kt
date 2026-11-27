package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.ConfirmDialog
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.platformOpensIn
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Screen

internal fun LazyListScope.emulators(state: AppState, open: (SettingsDialog) -> Unit) {
  sectionHeading("Emulators")
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
        "Checks whether an emulator is still running before a game starts, and offers to " +
          "close it, so a newer save from RomM can't be overwritten.",
      ) {
        if (on) state.settings = state.settings.copy(closeEmulators = false)
        else open(SettingsDialog.CloseEmulators)
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
    Text("No platforms", color = Placeholder.mutedText)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (platform in loaded) {
      val slug = PlatformSlug(platform.slug)
      val opensIn = platformOpensIn(slug, state.settings.launch, installed)
      SettingRow(
        state.settingsView,
        "settings:emulators:${platform.slug}",
        platform.name,
        opensIn ?: "No app installed",
        "The app its games open in, unless a game or a collection chooses another.",
      ) {
        state.navigator.push(Screen.OpenWith(OpenWithScope.Platform(slug), platform.name))
      }
    }
  }
}

/** Asks before turning on closing emulators. */
@Composable
internal fun CloseEmulatorsDialog(onTurnOn: () -> Unit, onDismissRequest: () -> Unit) {
  ConfirmDialog(
    "closeEmulators",
    "Close emulators",
    "Turn on",
    onConfirm = onTurnOn,
    onDismissRequest = onDismissRequest,
    fact = "Uses root · Unsaved progress can be lost",
  )
}
