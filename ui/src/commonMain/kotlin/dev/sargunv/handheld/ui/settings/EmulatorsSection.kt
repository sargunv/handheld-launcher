package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.ConfirmDialog
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.platformOpensIn
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Screen

/**
 * Every platform in the library, with the app its games open in when nothing else chooses, once
 * both the [platforms] and the [apps] installed are read.
 */
internal fun LazyListScope.emulators(
  state: AppState,
  platforms: List<Platform>?,
  apps: List<InstalledApp>?,
  open: (SettingsDialog) -> Unit,
) {
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
  if (platforms == null || apps == null) return
  if (platforms.isEmpty()) {
    item(key = "noPlatforms") { Text("No platforms", color = Placeholder.mutedText) }
  }
  items(platforms, key = { it.slug }) { platform ->
    val slug = PlatformSlug(platform.slug)
    SettingRow(
      view,
      "settings:emulators:${platform.slug}",
      platform.name,
      platformOpensIn(slug, state.settings.launch, apps) ?: "No app installed",
      "The app its games open in, unless a game or a collection chooses another.",
    ) {
      state.navigator.push(Screen.OpenWith(OpenWithScope.Platform(slug), platform.name))
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
