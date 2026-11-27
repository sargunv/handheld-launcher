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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.ui.Placeholder
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
import kotlinx.coroutines.launch

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
 * What the Settings page shows, which both screens follow. Signed out of [romm], it offers only
 * what needs no server: Controls, RomM (which says so) and About. It offers RetroAchievements when
 * [achievements] is.
 */
@Stable
class SettingsView(private val romm: RommSession, private val achievements: Boolean) {
  /** The section last focused in the list or jumped to, which shows while it's offered. */
  var section: SettingsSection by mutableStateOf(SettingsSection.Controls)
    internal set

  internal val offered: List<SettingsSection>
    @Composable
    get() {
      val signedIn = romm.state.collectAsState().value.signedIn == true
      return SettingsSection.entries.filter {
        when (it) {
          SettingsSection.RetroAchievements -> signedIn && achievements
          SettingsSection.Emulators,
          SettingsSection.Storage -> signedIn
          else -> true
        }
      }
    }

  /** The section on the right and on the lower screen: [section], or else the first offered. */
  internal val shown: SettingsSection
    @Composable
    get() {
      val offered = offered
      return section.takeIf { it in offered } ?: offered.first()
    }

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
 */
@Composable
internal fun SettingsPage(state: AppState, backHint: String = "Home") {
  val view = state.settingsView
  val romm by state.romm.state.collectAsState()
  val sync by state.romm.sync.collectAsState()
  val sections = view.offered
  val section = view.shown
  val scope = rememberCoroutineScope()
  var dialog by rememberSaveable(stateSaver = SettingsDialog.saver) { mutableStateOf(null) }
  val open = { it: SettingsDialog -> dialog = it }
  val close = { dialog = null }
  // Asked again after each sync, since the account's permissions can change.
  val uploadAccess by
    produceState<UploadAccess?>(null, romm, sync, section) {
      if (section == SettingsSection.RomM) value = state.romm.uploadAccess()
    }
  val storageSpaces by
    rememberStorageSpaces(state.downloads, active = section == SettingsSection.Storage)
  val mounted = storageSpaces.map { it.target }
  val platforms by
    produceState<List<Platform>?>(null, state.library, section) {
      if (section == SettingsSection.Emulators) state.library.platforms().collect { value = it }
    }
  val apps by
    produceState<List<InstalledApp>?>(null, state.apps, section) {
      if (section == SettingsSection.Emulators) value = state.apps.apps()
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
              view,
              it,
              selected = it == section,
              onFocus = { view.section = it },
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
              SettingsSection.RomM ->
                romm(state, romm, sync, uploadAccess, open) {
                  scope.launch { state.signOut(keepQueue = true) }
                }
              SettingsSection.RetroAchievements -> retroAchievements(state)
              SettingsSection.Emulators -> emulators(state, platforms, apps, open)
              SettingsSection.Storage ->
                storage(state, storageSpaces, storageRefused, toggleStorage, open)
              SettingsSection.About -> about(state, romm.signedIn == true)
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
    when (val shown = dialog) {
      null -> {}
      SettingsDialog.SignOut ->
        SignOutDialog(
          onSignOut = {
            close()
            scope.launch { state.signOut() }
          },
          onDismissRequest = close,
        )
      SettingsDialog.AllowUploads ->
        AllowUploadsDialog(
          UPLOADS_NOT_REQUESTED,
          onSignInAgain = {
            close()
            scope.launch { state.signOut(allowUploads = true, keepQueue = true) }
          },
          onDismissRequest = close,
        )
      SettingsDialog.CloseEmulators ->
        CloseEmulatorsDialog(
          onTurnOn = {
            state.settings = state.settings.copy(closeEmulators = true)
            close()
          },
          onDismissRequest = close,
        )
      // A restored menu shows once its storage is measured.
      is SettingsDialog.StorageMenu ->
        mounted
          .find { it.id == shown.id }
          ?.let { target ->
            StorageMenu(
              target,
              state.settings.storage,
              mounted,
              onMove = { state.settings = state.settings.copy(storage = it) },
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

  companion object {
    val saver =
      Saver<SettingsDialog?, String>(
        save = {
          when (it) {
            null -> null
            SignOut -> "signOut"
            AllowUploads -> "allowUploads"
            CloseEmulators -> "closeEmulators"
            is StorageMenu -> "storage:${it.id}"
          }
        },
        restore = {
          when {
            it == "signOut" -> SignOut
            it == "allowUploads" -> AllowUploads
            it == "closeEmulators" -> CloseEmulators
            it.startsWith("storage:") -> StorageMenu(it.removePrefix("storage:"))
            else -> null
          }
        },
      )
  }
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
    color = Placeholder.text,
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
  val section = view.shown
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      help?.label ?: section.title,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.testTag("settings:help:title").semantics { heading() },
    )
    Text(help?.text ?: section.description, color = Placeholder.mutedText)
  }
}
