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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.data.downloads.StorageSpace
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.achievements.RetroAchievementsPanel
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.LocalFocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.InstalledApp
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.platformOpensIn
import dev.sargunv.handheld.ui.input.AButtonPosition
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.signin.Help
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
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
  About("About", "The version, the license and source, and the third-party components' licenses.");

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

  /** What the lower screen says about the focused setting; null describes the section. */
  internal var help: Help? by mutableStateOf(null)

  /** The focused setting, when it has a menu, which the hints then offer. */
  internal var menuKey: String? by mutableStateOf(null)

  /** A section to move focus to once the page shows, for development tools. */
  internal var pendingJump: SettingsSection? by mutableStateOf(null)

  /** Shows [section], with focus on it in the list. */
  fun jumpTo(section: SettingsSection) {
    this.section = section
    help = null
    pendingJump = section
  }
}

/**
 * Settings: the sections in a list on the left, and the focused section's settings on the right.
 * Signed out, it offers only what needs no server: Controls, RomM (which says so) and About.
 */
@Composable
internal fun SettingsPage(state: AppState, backHint: String = "Home") {
  val view = state.settingsView
  val signedIn = state.signedIn == true
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
  var explainCloseEmulators by rememberSaveable { mutableStateOf(false) }
  var confirmSignOut by rememberSaveable { mutableStateOf(false) }
  // The storage whose menu is open, by its id.
  var storageMenu by rememberSaveable { mutableStateOf<String?>(null) }
  // Whether turning off the last storage that's on was just refused.
  var storageRefused by remember { mutableStateOf(false) }
  val storageSpaces by
    produceState(emptyList<StorageSpace>(), state.downloads, section) {
      if (section != SettingsSection.Storage) return@produceState
      // Downloads and other apps change it, so it's read again every few seconds.
      while (true) {
        value = state.downloads.storageSpace()
        delay(5.seconds)
      }
    }
  fun setStorage(change: StorageSettings) {
    state.settings = state.settings.copy(storage = change)
  }
  fun toggleStorage(target: StorageTarget) {
    val settings = state.settings.storage
    val mounted = storageSpaces.map { it.target }
    val changed =
      if (settings.isOn(target)) settings.turnOff(target, mounted) else settings.turnOn(target)
    storageRefused = changed == null
    changed?.let(::setStorage)
  }
  val scope = rememberCoroutineScope()
  // The section's settings, which Right from the list enters at the top.
  val pane = remember { FocusRequester() }

  FocusScope(Modifier.fillMaxSize(), initialFocus = section.key) {
    val focusScope = LocalFocusScope.current
    LaunchedEffect(view.pendingJump) {
      val jump = view.pendingJump ?: return@LaunchedEffect
      // The list composes its items during layout, so wait for a frame to find them.
      withFrameNanos {}
      focusScope?.focus(jump.key)
      view.pendingJump = null
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
                view.help = null
                storageRefused = false
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
              SettingsSection.RomM ->
                romm(
                  state,
                  onSignOut = { confirmSignOut = true },
                  onSignInAgain = {
                    scope.launch { state.signOut() }
                  },
                )
              SettingsSection.RetroAchievements -> retroAchievements(state)
              SettingsSection.Emulators ->
                emulators(state, onTurnOnCloseEmulators = { explainCloseEmulators = true })
              SettingsSection.Storage ->
                storage(
                  state,
                  storageSpaces,
                  refused = storageRefused,
                  onToggle = ::toggleStorage,
                  onMenu = { storageMenu = it.id },
                )
              SettingsSection.About -> about(state)
            }
          }
        }
      }
      HintBar(
        listOf(
            Hint(Action.Confirm, "Choose"),
            Hint(Action.Back, backHint),
            Hint(Action.Menu, "Menu").takeIf { view.menuKey != null },
            Hint(Action.MainMenu, "Main menu"),
          )
          .filterNotNull(),
      )
    }
    val menuTarget = storageSpaces.find { it.target.id == storageMenu }?.target
    if (menuTarget != null) {
      StorageMenu(
        target = menuTarget,
        settings = state.settings.storage,
        mounted = storageSpaces.map { it.target },
        onMove = ::setStorage,
        onToggle = { toggleStorage(menuTarget) },
        onDismissRequest = { storageMenu = null },
      )
    }
    if (explainCloseEmulators) {
      CloseEmulatorsDialog(
        onTurnOn = {
          state.settings = state.settings.copy(closeEmulators = true)
          explainCloseEmulators = false
        },
        onDismissRequest = { explainCloseEmulators = false },
      )
    }
    if (confirmSignOut) {
      SignOutDialog(
        onSignOut = {
          confirmSignOut = false
          scope.launch { state.signOut() }
        },
        onDismissRequest = { confirmSignOut = false },
      )
    }
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
private fun SettingRow(
  view: SettingsView,
  key: String,
  label: String,
  value: String?,
  help: Help,
  enabled: Boolean = true,
  onMenu: (() -> Unit)? = null,
  onConfirm: (() -> Unit)? = null,
) {
  Row(
    Modifier.fillMaxWidth()
      .onFocusChanged {
        if (it.isFocused) view.help = help
        when {
          it.isFocused && onMenu != null -> view.menuKey = key
          it.isFocused || view.menuKey == key -> view.menuKey = null
        }
      }
      .then(if (enabled) Modifier else Modifier.semantics { disabled() })
      .focusItem(key, onConfirm = onConfirm) { action ->
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

private fun LazyListScope.sectionHeading(title: String) {
  item(key = "heading") {
    Text(
      title,
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
  }
}

private fun LazyListScope.note(key: String, text: String) {
  item(key = key) { Text(text, color = Placeholder.mutedText, modifier = Modifier.testTag(key)) }
}

private fun LazyListScope.controls(state: AppState) {
  sectionHeading("Controls")
  item(key = "confirm") {
    val confirm =
      when (state.inputSettings.confirmButton) {
        ConfirmButton.A -> PadButton.A
        ConfirmButton.B -> PadButton.B
      }
    SettingRow(
      state.settingsView,
      "settings:confirm",
      "Confirm button",
      confirm.label,
      Help("Confirm button", "Which face button confirms. The other one goes back."),
      onConfirm = state::swapConfirmButton,
    )
  }
  item(key = "aButton") {
    val position =
      when (state.inputSettings.aButtonPosition) {
        AButtonPosition.South -> "Bottom"
        AButtonPosition.East -> "Right"
      }
    SettingRow(
      state.settingsView,
      "settings:aButton",
      "A button position",
      position,
      Help(
        "A button position",
        "Where this device's A button sits, so games in Built-in get the button they expect " +
          "in each place.",
      ),
      onConfirm = state::switchAButtonPosition,
    )
  }
}

private fun LazyListScope.romm(
  state: AppState,
  onSignOut: () -> Unit,
  onSignInAgain: () -> Unit,
) {
  sectionHeading("RomM")
  val view = state.settingsView
  if (state.signedIn != true) {
    item(key = "signedOut") {
      SettingRow(
        view,
        "settings:romm:server",
        "Server",
        "Not signed in",
        Help("Not signed in", "Sign in on the main screen to reach your library."),
      )
    }
    return
  }
  item(key = "server") {
    val server by produceState<String?>(null) { value = state.credentials.load()?.baseUrl }
    SettingRow(
      view,
      "settings:romm:server",
      "Server",
      server ?: "",
      Help("Server", "The RomM server this device's library comes from."),
    )
  }
  item(key = "account") {
    // Asked of the server each time, since nothing keeps it.
    val account by
      produceState<String?>(null, state.sync) {
        value =
          try {
            state.romm.client()?.use { it.me().username } ?: "Unknown"
          } catch (_: RommException) {
            "Couldn't ask the server"
          }
      }
    SettingRow(
      view,
      "settings:romm:account",
      "Account",
      account ?: "",
      Help("Account", "The RomM user this device signed in as."),
    )
  }
  item(key = "library") {
    SettingRow(
      view,
      "settings:romm:sync",
      "Library",
      syncSummary(state.sync),
      Help("Library", "How the latest sync of the library with the server went."),
    )
  }
  if (state.sync == SyncStatus.Unauthorized) {
    item(key = "refused") {
      Text(
        "RomM refused this device's sign-in, so the library can't sync. It may have been signed " +
          "out on the server, or its token may have expired. Sign in again to sync.",
        color = Placeholder.text,
        modifier =
          Modifier.testTag("settings:romm:refused").semantics {
            liveRegion = LiveRegionMode.Polite
          },
      )
    }
    item(key = "signInAgain") {
      SettingRow(
        view,
        "settings:romm:signInAgain",
        "Sign in again",
        null,
        Help(
          "Sign in again",
          "Goes to the sign-in, with this server filled in. Your games and saves stay on this " +
            "device.",
        ),
        onConfirm = onSignInAgain,
      )
    }
  }
  item(key = "syncNow") {
    val syncing = state.sync == SyncStatus.Syncing || state.sync == SyncStatus.FirstSync
    SettingRow(
      view,
      "settings:romm:syncNow",
      "Sync now",
      null,
      Help("Sync now", "Syncs the library with the server now, rather than at the next start."),
      enabled = !syncing,
      onConfirm = { if (!syncing) state.syncNow() },
    )
  }
  item(key = "signOut") {
    SettingRow(
      view,
      "settings:romm:signOut",
      "Sign out",
      null,
      Help(
        "Sign out",
        "Signs this device out of the server. Your downloaded games and saves stay on this device.",
      ),
      onConfirm = onSignOut,
    )
  }
}

/** What the RomM section says about the latest sync. */
internal fun syncSummary(sync: SyncStatus): String =
  when (sync) {
    SyncStatus.NotStarted -> "Not synced yet"
    SyncStatus.FirstSync,
    SyncStatus.Syncing -> "Syncing"
    SyncStatus.Synced -> "Synced"
    SyncStatus.Failed -> "Couldn't reach the server"
    SyncStatus.Unauthorized -> "RomM refused the sign-in"
  }

private fun LazyListScope.retroAchievements(state: AppState) {
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

private fun LazyListScope.emulators(state: AppState, onTurnOnCloseEmulators: () -> Unit) {
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
        Help(
          "Close emulators",
          "Checks whether an emulator is still running before a game starts, and offers to " +
            "close it, so a newer save from RomM can't be overwritten.",
        ),
      ) {
        if (on) state.settings = state.settings.copy(closeEmulators = false)
        else onTurnOnCloseEmulators()
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
  item(key = "platformRows") { PlatformRows(state) }
}

/** Every platform in the library, with the app its games open in when nothing else chooses. */
@Composable
private fun PlatformRows(state: AppState) {
  val platforms by remember(state.library) { state.library.platforms() }.collectAsState(null)
  val apps by produceState<List<InstalledApp>?>(null, state.apps) { value = state.apps.apps() }
  val loaded = platforms ?: return
  val installed = apps ?: return
  if (loaded.isEmpty()) {
    Text("Your library has no platforms yet.", color = Placeholder.mutedText)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (platform in loaded) {
      val slug = PlatformSlug(platform.slug)
      val opensIn = platformOpensIn(slug, state.settings.launch, installed)
      val chosen = state.settings.launch.platforms.containsKey(platform.slug)
      SettingRow(
        state.settingsView,
        "settings:emulators:${platform.slug}",
        platform.name,
        opensIn ?: "No app installed",
        Help(
          platform.name,
          (opensIn?.let { "Its games open in $it" } ?: "No app on this device can open its games") +
            (if (chosen) ", which you chose." else ", by default.") +
            " Choose another app for all its games. A game's or collection's own choice still " +
            "comes first.",
        ),
      ) {
        state.navigator.push(Screen.OpenWith(OpenWithScope.Platform(slug), platform.name))
      }
    }
  }
}

/**
 * The mounted storage, in the order new games fill it, each turned on or off with Confirm and moved
 * from its menu.
 *
 * @param refused whether turning off the last storage that's on was just refused, which a note
 *   explains.
 */
private fun LazyListScope.storage(
  state: AppState,
  spaces: List<StorageSpace>,
  refused: Boolean,
  onToggle: (StorageTarget) -> Unit,
  onMenu: (StorageTarget) -> Unit,
) {
  sectionHeading("Storage")
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
          Help(
            target.label,
            "$free, at ${target.root}. New games go to the first storage that's on and has " +
              "room, from the top. Games already on storage that's off still show and play.",
          ),
          onMenu = { onMenu(target) },
          onConfirm = { onToggle(target) },
        )
      }
    }
  }
  if (refused) {
    item(key = "refused") {
      Text(
        "One storage stays on, so new games have somewhere to go.",
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
    "New games go to the first of these that's on and has room. Confirm turns one on or off, " +
      "and its menu moves it.",
  )
}

/**
 * The menu on a storage: it moves [target] among the [mounted] storage, and turns it on or off.
 * Each choice closes the menu.
 */
@Composable
private fun StorageMenu(
  target: StorageTarget,
  settings: StorageSettings,
  mounted: List<StorageTarget>,
  onMove: (StorageSettings) -> Unit,
  onToggle: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  val shown = settings.arrange(mounted)
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
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

private fun LazyListScope.about(state: AppState) {
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
      enabled = state.signedIn == true,
      onConfirm = { state.navigator.push(Screen.About) }.takeIf { state.signedIn == true },
    )
  }
}

/** The lower screen on Settings: the focused setting, or the focused section. */
@Composable
internal fun SettingsSecondary(view: SettingsView) {
  val help = view.help
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      help?.title ?: view.section.title,
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.testTag("settings:help:title").semantics { heading() },
    )
    Text(help?.text ?: view.section.description, color = Placeholder.mutedText)
  }
}

/** Explains closing emulators before the player turns it on. */
@Composable
private fun CloseEmulatorsDialog(onTurnOn: () -> Unit, onDismissRequest: () -> Unit) {
  Dialog(
    onDismissRequest,
    Modifier.widthIn(max = 640.dp).background(Placeholder.dialog).padding(24.dp),
    initialFocus = "closeEmulators:cancel",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Close emulators", color = Placeholder.text, modifier = Modifier.semantics { heading() })
      Text(
        "This device has a built-in root service that any app can use. With this on, the " +
          "launcher uses it only to check whether an emulator is still running and to close it, " +
          "so a newer save from RomM can't be overwritten by the emulator's old one.",
        color = Placeholder.mutedText,
      )
      Text(
        "Closing skips the emulator's own exit, so progress since its last in-game save can be " +
          "lost. When you can, quit from the emulator's own menu instead.",
        color = Placeholder.mutedText,
      )
      MenuButton("closeEmulators:turnOn", "Turn on", onConfirm = onTurnOn)
      MenuButton("closeEmulators:cancel", "Cancel", onConfirm = onDismissRequest)
    }
  }
}

/** Asks before signing out. Cancel has focus first, so one press too many signs nothing out. */
@Composable
private fun SignOutDialog(onSignOut: () -> Unit, onDismissRequest: () -> Unit) {
  Dialog(
    onDismissRequest,
    Modifier.widthIn(max = 640.dp).background(Placeholder.dialog).padding(24.dp),
    initialFocus = "signOut:cancel",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Sign out of RomM?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        "Your downloaded games and saves stay on this device. Saves sync again once you sign in.",
        color = Placeholder.mutedText,
      )
      MenuButton("signOut:confirm", "Sign out", onConfirm = onSignOut)
      MenuButton("signOut:cancel", "Cancel", onConfirm = onDismissRequest)
    }
  }
}
