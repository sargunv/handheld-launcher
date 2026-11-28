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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.ui.Palette
import dev.sargunv.handheld.ui.downloads.rememberStorageSpaces
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.HelpHolder
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.focus.help
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.signedIn
import dev.sargunv.handheld.ui.signin.AllowUploadsDialog
import dev.sargunv.handheld.ui.signin.UPLOADS_NOT_REQUESTED
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

/**
 * The sections Settings offers: Controls and About, and the rest once [signedIn], with
 * RetroAchievements only on platforms that offer [achievements].
 */
internal fun settingsSections(signedIn: Boolean, achievements: Boolean): List<SettingsSection> =
  SettingsSection.entries.filter {
    when (it) {
      SettingsSection.Controls,
      SettingsSection.About -> true
      SettingsSection.RetroAchievements -> signedIn && achievements
      SettingsSection.RomM,
      SettingsSection.Emulators,
      SettingsSection.Storage -> signedIn
    }
  }

/** What the Settings page shows, which both screens follow. */
@Stable
class SettingsView {
  /** The section last focused in the list or jumped to, which shows while it's offered. */
  var section: SettingsSection by mutableStateOf(SettingsSection.Controls)
    internal set

  /**
   * The section on the right and on the lower screen: [section], or else the first of [offered].
   */
  internal fun shown(offered: List<SettingsSection>): SettingsSection =
    section.takeIf { it in offered } ?: offered.first()

  /** What the lower screen says about the focused setting, in place of the section's. */
  internal val help = HelpHolder()

  /** Sections to move focus to once the page shows, for development tools. */
  internal val jumps = Channel<SettingsSection>(Channel.CONFLATED)

  /** Shows [section], with focus on it in the list. */
  fun jumpTo(section: SettingsSection) {
    this.section = section
    jumps.trySend(section)
  }
}

/**
 * Settings: the sections in a list on the left, and the focused section's settings on the right.
 * About's row opens it with [onAbout].
 */
@Composable
internal fun SettingsPage(state: AppState, onAbout: () -> Unit) {
  val view = state.settingsView
  val romm by state.data.romm.state.collectAsState()
  val sync by state.data.romm.sync.collectAsState()
  val uploadAccess by state.data.romm.uploadAccess.collectAsState()
  val settings by state.data.settings.settings.collectAsState()
  val raAccount = state.platform.retroAchievements?.account?.collectAsState()?.value
  val raAttempt = state.platform.retroAchievements?.attempt?.collectAsState()?.value
  val signedIn = romm.signedIn == true
  val sections = settingsSections(signedIn, state.retroAchievements != null)
  val section = view.shown(sections)
  var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
  val open = { it: SettingsDialog -> dialog = it }
  val close = { dialog = null }
  val storageSpaces by
    rememberStorageSpaces(state.data.downloads, active = section == SettingsSection.Storage)
  val mounted = storageSpaces.map { it.target }
  val platforms by
    produceState<List<Platform>?>(null, state.data.library, section) {
      if (section == SettingsSection.Emulators)
        state.data.library.platforms().collect { value = it }
    }
  val apps by
    produceState<List<InstalledApp>?>(null, state.platform.apps, section) {
      if (section == SettingsSection.Emulators) value = state.platform.apps.apps()
    }
  var storageRefused by remember(section) { mutableStateOf(false) }
  val toggleStorage = { target: StorageTarget ->
    storageRefused = !state.toggleStorage(target, mounted)
  }
  // The section's settings, which Right from the list enters at the top.
  val pane = remember { FocusRequester() }

  FocusScope(Modifier.fillMaxSize(), initialFocus = section.key) {
    val focusScope = LocalFocusScope.current
    LaunchedEffect(view, focusScope) {
      for (jump in view.jumps) focusScope?.focus(jump.key)
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
              view,
              it,
              selected = it == section,
              onFocus = { view.section = it },
              // A section whose settings are still loading keeps focus in the list.
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
              SettingsSection.Controls -> controls(state, settings.input)
              SettingsSection.RomM ->
                (romm as? RommAccount)?.let { account ->
                  romm(state, account, sync, uploadAccess, open) {
                    state.signOut(keepQueue = true)
                  }
                }
              SettingsSection.RetroAchievements ->
                retroAchievements(state, settings.achievements, raAccount, raAttempt)
              SettingsSection.Emulators -> emulators(state, settings, platforms, apps, open)
              SettingsSection.Storage ->
                storage(state, settings.storage, storageSpaces, storageRefused, toggleStorage, open)
              SettingsSection.About -> about(state, onAbout)
            }
          }
        }
      }
      HintBar(
        listOfNotNull(
          Hint(Action.Confirm, "Choose"),
          // Signed in, Settings is a place, which Back leaves for Home.
          Hint(Action.Back, if (signedIn) "Home" else "Back"),
          Hint(Action.Menu, "Menu").takeIf { view.help.shown?.hasMenu == true },
        ),
      )
    }
    when (val shown = dialog) {
      null -> {}
      SettingsDialog.SignOut ->
        SignOutDialog(
          onSignOut = {
            close()
            state.signOut()
          },
          onDismissRequest = close,
        )
      SettingsDialog.AllowUploads ->
        AllowUploadsDialog(
          UPLOADS_NOT_REQUESTED,
          onSignInAgain = {
            close()
            state.signOut(allowUploads = true, keepQueue = true)
          },
          onDismissRequest = close,
        )
      SettingsDialog.CloseEmulators ->
        CloseEmulatorsDialog(
          onTurnOn = {
            state.data.settings.update { it.copy(closeEmulators = true) }
            close()
          },
          onDismissRequest = close,
        )
      is SettingsDialog.StorageMenu ->
        mounted
          .find { it.id == shown.id }
          ?.let { target ->
            StorageMenu(
              target,
              settings.storage,
              mounted,
              onMove = { moved -> state.data.settings.update { it.copy(storage = moved) } },
              onToggle = { toggleStorage(target) },
              onDismissRequest = close,
            )
          }
    }
  }
}

/** The dialog open over Settings. */
internal sealed interface SettingsDialog {
  data object SignOut : SettingsDialog

  data object AllowUploads : SettingsDialog

  data object CloseEmulators : SettingsDialog

  /** The menu on the storage with [id]. */
  data class StorageMenu(val id: String) : SettingsDialog
}

/** Directions that can't leave a column through its ends. */
private val VerticalWalls = setOf(FocusDirection.Up, FocusDirection.Down)

@Composable
private fun SectionRow(
  view: SettingsView,
  section: SettingsSection,
  selected: Boolean,
  onFocus: () -> Unit,
  onRight: () -> Boolean,
) {
  Text(
    section.title,
    color = Palette.text,
    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    modifier =
      Modifier.fillMaxWidth()
        .semantics { this.selected = selected }
        .help(view.help, section.key, section.title, help = null)
        .onFocusChanged { if (it.isFocused) onFocus() }
        // Confirming selects it too, for accessibility services, which move their own focus.
        .focusItem(section.key, onConfirm = onFocus) { action ->
          action == Action.Right && onRight()
        }
        .background(if (selected) Palette.tile else Palette.background)
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
      .background(Palette.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Text(label, color = Palette.text, modifier = Modifier.weight(1f))
    value?.let { Text(it, color = Palette.mutedText) }
  }
}

internal fun LazyListScope.sectionHeading(title: String) {
  item(key = "heading") {
    Text(
      title,
      color = Palette.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
  }
}

/** The lower screen on Settings: the focused setting, or the focused section. */
@Composable
internal fun SettingsSecondary(state: AppState) {
  val view = state.settingsView
  val help = view.help.shown
  val signedIn = state.data.romm.state.collectAsState().value.signedIn == true
  val section = view.shown(settingsSections(signedIn, state.retroAchievements != null))
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      help?.label ?: section.title,
      color = Palette.text,
      fontSize = 24.sp,
      modifier = Modifier.testTag("settings:help:title").semantics { heading() },
    )
    Text(help?.text ?: section.description, color = Palette.mutedText)
  }
}
