package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Screen

internal fun LazyListScope.about(state: AppState) {
  sectionHeading("About")
  item(key = "about") {
    SettingRow(
      state.settingsView,
      "settings:about:open",
      "Version, licence and source",
      state.appInfo.version,
      "The app's version, licence and source, and its components' licences.",
      // About opens over the places, which the sign-in doesn't have.
      enabled = state.account.signedIn == true,
      onConfirm = { state.navigator.push(Screen.About) },
    )
  }
}
