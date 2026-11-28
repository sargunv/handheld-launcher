package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.composeunstyled.Text
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.dialog.ConfirmDialog
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.chainFor
import dev.sargunv.handheld.ui.game.opensIn
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Screen

/**
 * The Emulators section, with the library's [platforms] and installed [apps], each null while it's
 * read.
 */
internal fun LazyListScope.emulators(
  state: AppState,
  settings: Settings,
  platforms: List<Platform>?,
  apps: List<InstalledApp>?,
  open: (SettingsDialog) -> Unit,
) {
  sectionHeading("Emulators")
  val view = state.settingsView
  // Only on devices with a root service the launcher can close emulators with.
  if (state.platform.launcher.canCloseEmulators) {
    item(key = "closeEmulators") {
      val on = settings.closeEmulators
      SettingRow(
        view,
        "settings:closeEmulators",
        "Close emulators",
        if (on) "On" else "Off",
        "Offers to close a running emulator before a game starts",
      ) {
        if (on) state.data.settings.update { it.copy(closeEmulators = false) }
        else open(SettingsDialog.CloseEmulators)
      }
    }
  }
  item(key = "platforms") {
    Text(
      "Each platform's games open in",
      color = Palette.mutedText,
      modifier = Modifier.semantics { heading() },
    )
  }
  if (platforms == null || apps == null) return
  if (platforms.isEmpty()) {
    item(key = "noPlatforms") { Text("No platforms", color = Palette.mutedText) }
  }
  items(platforms, key = { it.slug.value }) { platform ->
    val scope = OpenWithScope.Platform(platform.slug)
    SettingRow(
      view,
      "settings:emulators:${platform.slug}",
      platform.name,
      opensIn(chainFor(scope, platform.slug, emptySet(), settings.launch, apps), apps)
        ?: "No app installed",
      "The app its games open in, unless a game or a collection chooses another",
    ) {
      state.navigator.push(Screen.OpenWith(scope, platform.name))
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
