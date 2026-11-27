package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import dev.sargunv.handheld.ui.places.AppState

/** The About section, whose row opens About with [onOpen]. */
internal fun LazyListScope.about(state: AppState, onOpen: () -> Unit) {
  sectionHeading("About")
  item(key = "about") {
    SettingRow(
      state.settingsView,
      "settings:about:open",
      "Version, licence and source",
      state.appInfo.version,
      SettingsSection.About.description,
      onConfirm = onOpen,
    )
  }
}
