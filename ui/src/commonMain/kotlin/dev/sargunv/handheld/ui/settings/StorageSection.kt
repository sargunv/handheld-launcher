package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.sargunv.handheld.ui.signin.Help

@Composable
internal fun rememberStorageSection(state: AppState): StorageSection =
  rememberSaveable(state, saver = StorageSection.saver(state)) { StorageSection(state) }

internal class StorageSection(val state: AppState) {
  /** The storage whose menu is open, by its id. */
  private var menu: String? by mutableStateOf(null)

  /** Whether turning off the last storage that's on was just refused. */
  var refused by mutableStateOf(false)

  fun openMenu(target: StorageTarget) {
    menu = target.id
  }

  fun toggle(target: StorageTarget, mounted: List<StorageTarget>) {
    val settings = state.settings.storage
    val changed =
      if (settings.isOn(target)) settings.turnOff(target, mounted) else settings.turnOn(target)
    refused = changed == null
    changed?.let(::set)
  }

  private fun set(storage: StorageSettings) {
    state.settings = state.settings.copy(storage = storage)
  }

  @Composable
  fun Dialogs(spaces: List<StorageSpace>) {
    val target = spaces.find { it.target.id == menu }?.target ?: return
    StorageMenu(
      target = target,
      settings = state.settings.storage,
      mounted = spaces.map { it.target },
      onMove = ::set,
      onToggle = { toggle(target, spaces.map { it.target }) },
      onDismissRequest = { menu = null },
    )
  }

  companion object {
    fun saver(state: AppState) =
      Saver<StorageSection, String>(
        save = { it.menu },
        restore = { StorageSection(state).apply { menu = it } },
      )
  }
}

private fun LazyListScope.note(key: String, text: String) {
  item(key = key) { Text(text, color = Placeholder.mutedText, modifier = Modifier.testTag(key)) }
}

/**
 * The mounted storage, in the order new games fill it, each turned on or off with Confirm and moved
 * from its menu.
 */
internal fun LazyListScope.storage(section: StorageSection, spaces: List<StorageSpace>) {
  sectionHeading("Storage")
  val state = section.state
  val settings = state.settings.storage
  val byId = spaces.associateBy { it.target.id }
  val view = state.settingsView
  item(key = "volumes") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      for (target in settings.arrange(spaces.map { it.target })) {
        val free = "${formatSize(byId.getValue(target.id).freeBytes)} free"
        val on = settings.isOn(target)
        SettingRow(
          view,
          "settings:storage:${target.id}",
          target.label,
          "$free · ${if (on) "On" else "Off"}",
          Help(target.label, "$free, at ${target.root}"),
          onMenu = { section.openMenu(target) },
          onConfirm = { section.toggle(target, spaces.map { it.target }) },
        )
      }
    }
  }
  if (section.refused) {
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
  note(
    "settings:storage:note",
    "New games go to the first storage that's on and has room. Games on storage that's off " +
      "still play.",
  )
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
