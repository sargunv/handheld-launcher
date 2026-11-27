package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.signin.Help

internal fun LazyListScope.about(state: AppState) {
  sectionHeading("About")
  item(key = "about") {
    SettingRow(
      state.settingsView,
      "settings:about:open",
      "Version, license and source",
      state.appInfo.version,
      Help(
        "About",
        "The app's version, its license and where its source is, and the licenses of the " +
          "components it's built from.",
      ),
      // About opens over the places, which the sign-in doesn't have.
      enabled = state.account.signedIn == true,
      onConfirm = { state.navigator.push(Screen.About) }.takeIf { state.account.signedIn == true },
    )
  }
}
