package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.data.downloads.StorageSpace
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton

/**
 * The mounted storage, in the order new games fill it, each turned on or off with Confirm and moved
 * from its menu. [refused] says that turning off the last storage that's on was just refused.
 */
internal fun LazyListScope.storage(
  state: AppState,
  spaces: List<StorageSpace>,
  refused: Boolean,
  onToggle: (StorageTarget) -> Unit,
  open: (SettingsDialog) -> Unit,
) {
  sectionHeading("Storage")
  val settings = state.settings.storage
  val byId = spaces.associateBy { it.target.id }
  val view = state.settingsView
  items(settings.arrange(spaces.map { it.target }), key = { it.id }) { target ->
    val free = "${formatSize(byId.getValue(target.id).freeBytes)} free"
    val on = settings.isOn(target)
    SettingRow(
      view,
      "settings:storage:${target.id}",
      target.label,
      "$free · ${if (on) "On" else "Off"}",
      "$free, at ${target.root}",
      onMenu = { open(SettingsDialog.StorageMenu(target.id)) },
      onConfirm = { onToggle(target) },
    )
  }
  if (refused) {
    item(key = "refused") {
      Text(
        "Last storage stays on",
        color = Placeholder.text,
        modifier =
          Modifier.testTag("settings:storage:refused").semantics {
            liveRegion = LiveRegionMode.Polite
          },
      )
    }
  }
  item(key = "note") {
    Text(
      "New games go to the first storage that's on and has room. Games on storage that's off " +
        "still play.",
      color = Placeholder.mutedText,
      modifier = Modifier.testTag("settings:storage:note"),
    )
  }
}

/**
 * Turns [target] on or off among the [mounted] storage. Returns false, changing nothing, when it's
 * the last that's on.
 */
internal fun AppState.toggleStorage(target: StorageTarget, mounted: List<StorageTarget>): Boolean {
  var changed = false
  updateSettings { settings ->
    val storage = settings.storage
    val next =
      if (storage.isOn(target)) storage.turnOff(target, mounted) else storage.turnOn(target)
    changed = next != null
    next?.let { settings.copy(storage = it) } ?: settings
  }
  return changed
}

/**
 * The menu on a storage: it moves [target] among the [mounted] storage, and turns it on or off.
 * Each choice closes the menu.
 */
@Composable
internal fun StorageMenu(
  target: StorageTarget,
  settings: StorageSettings,
  mounted: List<StorageTarget>,
  onMove: (StorageSettings) -> Unit,
  onToggle: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  val shown = settings.arrange(mounted)
  Dialog(onDismissRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(target.label, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      if (shown.firstOrNull()?.id != target.id) {
        MenuButton("menu:up", "Move up") {
          onMove(settings.moveUp(target, mounted))
          onDismissRequest()
        }
      }
      if (shown.lastOrNull()?.id != target.id) {
        MenuButton("menu:down", "Move down") {
          onMove(settings.moveDown(target, mounted))
          onDismissRequest()
        }
      }
      MenuButton("menu:toggle", if (settings.isOn(target)) "Turn off" else "Turn on") {
        onToggle()
        onDismissRequest()
      }
    }
  }
}
