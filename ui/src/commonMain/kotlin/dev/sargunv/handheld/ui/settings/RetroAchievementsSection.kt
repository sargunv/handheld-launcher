package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import dev.sargunv.handheld.ui.achievements.RetroAchievementsPanel
import dev.sargunv.handheld.ui.places.AppState

internal fun LazyListScope.retroAchievements(state: AppState) {
  val achievements = state.retroAchievements ?: return
  item(key = "panel") {
    // The panel's items say nothing of their own on the lower screen, so it describes the section.
    Column(Modifier.onFocusChanged { if (it.hasFocus) state.settingsView.help = null }) {
      RetroAchievementsPanel(achievements, state.settings.achievements) {
        state.settings = state.settings.copy(achievements = it)
      }
    }
  }
}
