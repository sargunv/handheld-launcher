package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.downloads.rememberStorageSpaces
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.HelpHolder
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.focus.help
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.places.AppState
import kotlinx.coroutines.channels.Channel

/** The sections of Settings, in the order the page lists them. */
enum class SettingsSection(val title: String, internal val description: String) {
  Controls("Controls", "Which button confirms, and where the A button sits for games in Built-in."),
  RomM("RomM", "The server your library comes from, and this device's sign-in to it."),
  RetroAchievements(
    "RetroAchievements",
    "Your RetroAchievements account, and hardcore mode, for games in Built-in.",
  ),
  Emulators(
    "Emulators",
    "The app each platform's games open in, unless a game or a collection chooses another.",
  ),
  Storage(
    "Storage",
    "Where new games download to, in what order, and the room each place has left.",
  ),
  About("About", "The version, the licence and source, and the third-party components' licences.");

  /** The section's item in the list, and its test tag. */
  internal val key: String
    get() = "settings:$name"
}

/** What the Settings page shows, which both screens follow. */
@Stable
class SettingsView {
  /** The section on the right: the one focused in the list, or last focused there. */
  var section: SettingsSection by mutableStateOf(SettingsSection.Controls)
    internal set

  /** What the lower screen says about the focused setting, in place of the section's. */
  internal val help = HelpHolder()

  /** Sections to move focus to once the page shows, for development tools. */
  internal val jumps = Channel<SettingsSection>(Channel.CONFLATED)

  /** Shows [section], with focus on it in the list. */
  fun jumpTo(section: SettingsSection) {
    this.section = section
    help.shown = null
    jumps.trySend(section)
  }
}

/**
 * Settings: the sections in a list on the left, and the focused section's settings on the right.
 * Signed out, it offers only what needs no server: Controls, RomM (which says so) and About.
 */
@Composable
internal fun SettingsPage(state: AppState, backHint: String = "Home") {
  val view = state.settingsView
  val signedIn = state.account.signedIn == true
  val sections =
    SettingsSection.entries.filter {
      when (it) {
        SettingsSection.RetroAchievements -> signedIn && state.retroAchievements != null
        SettingsSection.Emulators,
        SettingsSection.Storage -> signedIn
        else -> true
      }
    }
  val section = view.section.takeIf { it in sections } ?: sections.first()
  // A section left from before a sign-out isn't offered at sign-in, so the lower screen follows.
  if (section != view.section) SideEffect { view.section = section }
  val rommSection = rememberRommSection(state, shown = section == SettingsSection.RomM)
  val emulatorsSection = rememberEmulatorsSection(state)
  val storageSection = rememberStorageSection(state)
  val storageSpaces by
    rememberStorageSpaces(state.downloads, active = section == SettingsSection.Storage)
  // The section's settings, which Right from the list enters at the top.
  val pane = remember { FocusRequester() }

  FocusScope(Modifier.fillMaxSize(), initialFocus = section.key) {
    val focusScope = LocalFocusScope.current
    LaunchedEffect(view, focusScope) {
      for (jump in view.jumps) {
        // The list composes its items during layout, so wait for a frame to find them.
        withFrameNanos {}
        focusScope?.focus(jump.key)
      }
    }
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp)) {
        // Up and Down stay within each column, so the section on the right doesn't change while
        // moving through its settings.
        FocusLazyColumn(
          Modifier.width(280.dp).fillMaxHeight(),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          walls = VerticalWalls,
        ) {
          items(sections, key = { it.key }) {
            SectionRow(
              it,
              selected = it == section,
              onFocus = {
                view.section = it
                view.help.shown = null
                storageSection.refused = false
              },
              // A section without settings to focus, such as About, keeps focus in the list.
              onRight = { pane.requestFocus(FocusDirection.Enter) || true },
            )
          }
        }
        Spacer(Modifier.width(32.dp))
        key(section) {
          FocusLazyColumn(
            Modifier.weight(1f).fillMaxHeight().focusRequester(pane),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            walls = VerticalWalls,
          ) {
            when (section) {
              SettingsSection.Controls -> controls(state)
              SettingsSection.RomM -> romm(rommSection)
              SettingsSection.RetroAchievements -> retroAchievements(state)
              SettingsSection.Emulators -> emulators(emulatorsSection)
              SettingsSection.Storage -> storage(storageSection, storageSpaces)
              SettingsSection.About -> about(state)
            }
          }
        }
      }
      HintBar(
        listOf(
            Hint(Action.Confirm, "Choose"),
            Hint(Action.Back, backHint),
            Hint(Action.Menu, "Menu").takeIf { view.help.shown?.hasMenu == true },
            Hint(Action.MainMenu, "Main menu"),
          )
          .filterNotNull(),
      )
    }
    storageSection.Dialogs(storageSpaces)
    emulatorsSection.Dialogs()
    rommSection.Dialogs()
  }
}

/** Directions that can't leave a column through its ends. */
private val VerticalWalls = setOf(FocusDirection.Up, FocusDirection.Down)

@Composable
private fun SectionRow(
  section: SettingsSection,
  selected: Boolean,
  onFocus: () -> Unit,
  onRight: () -> Boolean,
) {
  Text(
    section.title,
    color = Placeholder.text,
    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    modifier =
      Modifier.fillMaxWidth()
        .semantics { this.selected = selected }
        .onFocusChanged { if (it.isFocused) onFocus() }
        // Confirming selects it too, for accessibility services, which move their own focus.
        .focusItem(section.key, onConfirm = onFocus) { action ->
          action == Action.Right && onRight()
        }
        .background(if (selected) Placeholder.tile else Placeholder.background)
        .padding(horizontal = 20.dp, vertical = 12.dp),
  )
}

/**
 * One setting: its [label], and its [value] on the right when it has one. Focusing it puts [help]
 * on the lower screen. Without [onConfirm], it only shows something.
 *
 * @param enabled whether pressing it does something now. Accessibility services say when it
 *   doesn't; it still takes focus.
 * @param onMenu opens its menu, when it has one.
 */
@Composable
internal fun SettingRow(
  view: SettingsView,
  key: String,
  label: String,
  value: String?,
  help: String,
  enabled: Boolean = true,
  onMenu: (() -> Unit)? = null,
  onConfirm: (() -> Unit)? = null,
) {
  Row(
    Modifier.fillMaxWidth()
      .help(view.help, key, label, help, hasMenu = onMenu != null)
      .then(if (enabled) Modifier else Modifier.semantics { disabled() })
      .focusItem(
        key,
        onConfirm = onConfirm?.takeIf { enabled },
        role = Role.Button.takeIf { onConfirm != null },
      ) { action ->
        action == Action.Menu && onMenu?.invoke() != null
      }
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Text(label, color = Placeholder.text, modifier = Modifier.weight(1f))
    value?.let { Text(it, color = Placeholder.mutedText) }
  }
}

internal fun LazyListScope.sectionHeading(title: String) {
  item(key = "heading") {
    Text(
      title,
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
  }
}

/** The lower screen on Settings: the focused setting, or the focused section. */
@Composable
internal fun SettingsSecondary(view: SettingsView) {
  val help = view.help.shown
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      help?.label ?: view.section.title,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.testTag("settings:help:title").semantics { heading() },
    )
    Text(help?.text ?: view.section.description, color = Placeholder.mutedText)
  }
}
