package dev.sargunv.handheld.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composeunstyled.Text
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.about.AboutScreen
import dev.sargunv.handheld.ui.about.AboutSecondary
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.about.Component
import dev.sargunv.handheld.ui.about.LicenceScreen
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.downloads.DownloadsPlace
import dev.sargunv.handheld.ui.downloads.StorageSummary
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.GameScreen
import dev.sargunv.handheld.ui.game.GameSecondary
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.OpenWithScreen
import dev.sargunv.handheld.ui.game.OpenWithSecondary
import dev.sargunv.handheld.ui.home.HomePlace
import dev.sargunv.handheld.ui.input.AButtonPosition
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.library.DeviceState
import dev.sargunv.handheld.ui.library.LibraryPlace
import dev.sargunv.handheld.ui.library.LibrarySecondary
import dev.sargunv.handheld.ui.library.LibraryShelf
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.settings.SettingsPage
import dev.sargunv.handheld.ui.settings.SettingsSecondary
import dev.sargunv.handheld.ui.settings.SettingsView
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The top-level pages, which the main menu goes to. */
enum class Place(val title: String) {
  Home("Home"),
  Library("Library"),
  Downloads("Downloads"),
  Settings("Settings"),
}

/** The screens of the signed-in app. */
sealed interface Screen {
  /** The top-level places. */
  data object Places : Screen

  /** A game, which plays as it opens when [play] asks and the game is on the device. */
  data class Game(val romId: Long, val title: String, val play: Boolean = false) : Screen

  /**
   * The app games open in, for [scope]: a game, from its menu, a platform, from its menu or
   * Settings, or a collection's games on one platform, from the collection's menu. [title] names
   * the game, the platform or the collection.
   */
  data class OpenWith(val scope: OpenWithScope, val title: String) : Screen

  /** About the app: its version, its licence, and the components it's built from. */
  data object About : Screen

  /** The licence texts of the component [componentId], from About. */
  data class Licence(val componentId: String, val name: String) : Screen
}

/** How the library's latest sync with the server went. */
enum class SyncStatus {
  NotStarted,
  /** Syncing into an empty cache, which fills in a piece at a time. */
  FirstSync,
  Syncing,
  Synced,
  /** The server couldn't be reached, or failed. */
  Failed,
  /** The server refused the device's token, so syncing waits for a new sign-in. */
  Unauthorized;

  /** Whether the sync has finished, one way or another. */
  val isDone: Boolean
    get() = this == Synced || this == Failed || this == Unauthorized
}

/** What the lower screen describes: the item focused on the upper one. */
@Immutable
sealed interface Focused {
  data class OnGame(val rom: Rom) : Focused

  data class OnCollection(val collection: Collection) : Focused

  data class OnComponent(val component: Component) : Focused
}

/** State the app's two screens share. */
@Stable
class AppState(
  settings: Settings,
  val library: LibraryCache,
  val downloads: DownloadManager,
  val launcher: GameLauncher,
  val apps: AppCatalog,
  val credentials: CredentialStore,
  val httpEngine: () -> HttpClientEngine,
  /** The RetroAchievements account, on platforms whose games can earn achievements. */
  val retroAchievements: RetroAchievementsState? = null,
  /** What About says about this build. */
  val appInfo: AppInfo = AppInfo.Unknown,
) {
  var settings: Settings by mutableStateOf(settings)
  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)
  var sync: SyncStatus by mutableStateOf(SyncStatus.NotStarted)
  val libraryView = LibraryState()
  val settingsView = SettingsView()

  /** Signs in to the RomM server, and out of it. */
  val romm = RommSignIn(credentials, httpEngine)

  /**
   * Whether the device is signed in to a server. Null until the stored credentials have been read.
   */
  var signedIn: Boolean? by mutableStateOf(null)

  /**
   * Whether Settings shows over the sign-in, which offers only the settings that need no server.
   */
  var settingsOverSignIn: Boolean by mutableStateOf(false)

  /** Counts the syncs asked for after the first, so each one starts a sync. */
  var syncRequests: Int by mutableIntStateOf(0)
    private set

  /** Counts the sign-outs, so the sign-in after each one starts over. */
  var signOuts: Int by mutableIntStateOf(0)
    private set

  /** The server the device last signed out of, which the sign-in offers again. */
  var signedOutOf: String? by mutableStateOf(null)
    private set

  /**
   * The games on the device or on their way, by ROM id. A game missing here is only on the server.
   */
  var deviceStates: Map<Long, DeviceState> by mutableStateOf(emptyMap())

  /** Counts the times Home started over, so its focus and scroll positions start over too. */
  var homeResets: Int by mutableIntStateOf(0)
    private set

  /** Counts the plays that may have synced saves, so the save history loads again after each. */
  var savesChanged: Int by mutableIntStateOf(0)

  val inputSettings: InputSettings
    get() = settings.input

  /** The Library tab that shows, which stays between launches. */
  var libraryTab: LibraryTab
    get() = settings.library.tab
    set(value) {
      if (value != libraryTab)
        settings = settings.copy(library = settings.library.copy(tab = value))
    }

  /**
   * Shows [next]. The lower screen describes nothing until something there takes focus. Leaving the
   * Library closes a shelf that Home opened, since Back on it would go Home.
   */
  fun showPlace(next: Place) {
    if (next == place) return
    if (place == Place.Library && libraryView.returnsHome) {
      libraryView.isOpen = false
      libraryView.returnsHome = false
    }
    place = next
    focused = null
  }

  /**
   * Opens the games of the shelf with [shelfKey] on Library's [tab], as if the player had opened it
   * there. When [fromHome], Back on it goes Home.
   */
  fun openShelf(tab: LibraryTab, shelfKey: String, fromHome: Boolean = false) {
    showPlace(Place.Library)
    libraryTab = tab
    libraryView.focusShelf(tab, shelfKey)
    libraryView.isOpen = true
    libraryView.returnsHome = fromHome
    focused = null
  }

  /** Goes to [next] from the main menu: the screens over the places close, and [next] shows. */
  fun goTo(next: Place) {
    navigator.backToStart()
    showPlace(next)
  }

  /**
   * Starts over on Home, as the system's HOME does for a launcher: the screens over the places
   * close, and Home shows from its top with focus on its first item.
   */
  fun goHome() {
    navigator.backToStart()
    showPlace(Place.Home)
    homeResets++
  }

  /** Syncs the library with the server again, starting over if a sync is under way. */
  fun syncNow() {
    syncRequests++
  }

  /**
   * Signs out of the server and goes to the sign-in, which offers the same server again. The games
   * on the device, their saves and the library cache stay, but the downloads not yet done leave the
   * queue, since they're games of this server. The next sign-in opens on Home.
   *
   * Once begun, it finishes even if the screen that asked goes away.
   */
  suspend fun signOut() =
    withContext(NonCancellable) {
      signedOutOf = credentials.load()?.baseUrl
      // Before the credentials go, so the server hears of the canceled installs it asked for.
      for (download in downloads.queue.value) downloads.cancel(download.romId)
      romm.signOut()
      navigator.backToStart()
      place = Place.Home
      focused = null
      sync = SyncStatus.NotStarted
      homeResets++
      signOuts++
      signedIn = false
    }

  /**
   * Shows [target], on top of the places, as if the player had gone there. A game or platform that
   * the cache doesn't hold yet shows once the sync under way brings it, if it does.
   */
  suspend fun open(target: OpenTarget) {
    navigator.backToStart()
    when (target) {
      is OpenTarget.AtPlace -> showPlace(target.place)
      is OpenTarget.Game -> {
        val rom = library.rom(target.romId) ?: awaitSync().let { library.rom(target.romId) }
        if (rom != null) navigator.push(Screen.Game(rom.id, rom.title))
      }
      is OpenTarget.Library -> {
        showPlace(Place.Library)
        libraryView.isOpen = false
        libraryView.returnsHome = false
        target.tab?.let { libraryTab = it }
      }
      is OpenTarget.LibraryPlatform -> {
        showPlace(Place.Library)
        libraryView.isOpen = false
        suspend fun find() = library.platforms().first().find { it.slug == target.slug }
        val platform = find() ?: awaitSync().let { find() } ?: return
        openShelf(LibraryTab.Platforms, LibraryShelf.OfPlatform(platform).key)
      }
      is OpenTarget.LibraryCollection -> {
        showPlace(Place.Library)
        libraryView.isOpen = false
        suspend fun find() =
          library.collections().first().find { it.kind == target.kind && it.id == target.id }
        val collection = find() ?: awaitSync().let { find() } ?: return
        openShelf(LibraryTab.Collections, LibraryShelf.OfCollection(collection).key)
      }
      is OpenTarget.Settings -> {
        showPlace(Place.Settings)
        target.section?.let(settingsView::jumpTo)
      }
    }
  }

  private suspend fun awaitSync() {
    snapshotFlow { sync }.first { it.isDone }
  }

  fun swapConfirmButton() {
    val swapped =
      when (inputSettings.confirmButton) {
        ConfirmButton.A -> ConfirmButton.B
        ConfirmButton.B -> ConfirmButton.A
      }
    settings = settings.copy(input = inputSettings.copy(confirmButton = swapped))
  }

  fun switchAButtonPosition() {
    val switched =
      when (inputSettings.aButtonPosition) {
        AButtonPosition.South -> AButtonPosition.East
        AButtonPosition.East -> AButtonPosition.South
      }
    settings = settings.copy(input = inputSettings.copy(aButtonPosition = switched))
  }
}

/** The signed-in app's top screen. */
@Composable
fun MainPrimary(state: AppState) {
  NavigationHost(state.navigator, Modifier.fillMaxSize().background(Placeholder.background)) {
    screen ->
    when (screen) {
      Screen.Places -> NavEntry(screen) { PlacesScreen(state) }
      is Screen.Game -> NavEntry(screen) { GameScreen(state, screen) }
      is Screen.OpenWith -> NavEntry(screen) { OpenWithScreen(state, screen) }
      Screen.About -> NavEntry(screen) { AboutScreen(state) }
      is Screen.Licence -> NavEntry(screen) { LicenceScreen(state, screen) }
    }
  }
}

/** The signed-in app's bottom screen: it describes the focused item. */
@Composable
fun MainSecondary(state: AppState) {
  Column(
    Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    val screen = state.navigator.current
    if (screen is Screen.Game) {
      GameSecondary(state, screen)
      return@Column
    }
    if (screen is Screen.OpenWith) {
      OpenWithSecondary(state, screen)
      return@Column
    }
    if (screen == Screen.About || screen is Screen.Licence) {
      AboutSecondary(state, screen)
      return@Column
    }
    val focused = state.focused
    when {
      state.place == Place.Downloads -> StorageSummary(state.downloads)
      state.place == Place.Library -> LibrarySecondary(state)
      state.place == Place.Settings -> SettingsSecondary(state.settingsView)
      focused is Focused.OnGame -> GameDescription(state, focused.rom)
      focused is Focused.OnCollection -> {
        Text(
          focused.collection.name,
          color = Placeholder.text,
          fontSize = 24.sp,
          modifier = Modifier.semantics { heading() },
        )
        Text(gameCount(focused.collection.romCount), color = Placeholder.mutedText)
        if (focused.collection.description.isNotBlank()) {
          Text(focused.collection.description, color = Placeholder.mutedText)
        }
      }
    }
  }
}

/** The lower screen's description of a game. */
@Composable
internal fun GameDescription(state: AppState, rom: Rom) {
  val library = state.library
  val platforms by remember(library) { library.platforms() }.collectAsState(emptyList())
  Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Cover(
      rom.coverLargeUrl ?: rom.coverSmallUrl,
      rom.title,
      Modifier.size(180.dp, 240.dp),
      description = null,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        rom.title,
        color = Placeholder.text,
        fontSize = 24.sp,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        platforms.find { it.id == rom.platformId }?.name ?: rom.platformSlug,
        color = Placeholder.mutedText,
      )
      val onDevice =
        when (state.deviceStates[rom.id]) {
          DeviceState.OnDevice -> "On this device"
          is DeviceState.Downloading -> "Downloading"
          null -> "Not on this device"
        }
      Text(onDevice, color = Placeholder.mutedText)
    }
  }
}

internal fun gameCount(count: Int) = if (count == 1) "1 game" else "$count games"

/**
 * What accessibility services say for a game's cover: its title, and whether it's on the device,
 * which the cover doesn't show.
 */
internal fun coverDescription(rom: Rom, state: DeviceState?): String {
  val where =
    when (state) {
      DeviceState.OnDevice -> "on this device"
      is DeviceState.Downloading ->
        state.progress?.let { "downloading ${(it * 100).toInt()}%" } ?: "downloading"
      null -> "not on this device"
    }
  return "${rom.title}, $where"
}

/**
 * The top-level places, one at a time under a header that names it. Back on any place but Home goes
 * to Home; on Home it does nothing.
 */
@Composable
private fun PlacesScreen(state: AppState) {
  val places = rememberSaveableStateHolder()
  // The system back gesture goes Home too, as Back does.
  if (state.place != Place.Home && LocalNavigationEventDispatcherOwner.current != null) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { state.showPlace(Place.Home) },
    )
  }
  Column(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Back && state.place != Place.Home) {
        state.showPlace(Place.Home)
        true
      } else {
        false
      }
    },
  ) {
    PlaceHeader(state.place, state.sync)
    Box(Modifier.fillMaxWidth().weight(1f)) {
      // Each place is a focus scope, which keeps its focus and scroll position while another
      // place shows.
      places.SaveableStateProvider(state.place.name) {
        when (state.place) {
          // A new key starts Home's state over, focus and scroll positions included.
          Place.Home -> key(state.homeResets) { HomePlace(state) }
          Place.Library -> LibraryPlace(state)
          Place.Downloads ->
            DownloadsPlace(state.downloads) {
              state.navigator.push(Screen.Game(it.romId, it.title))
            }
          Place.Settings -> SettingsPage(state)
        }
      }
    }
  }
}

/** The place's name, and how the sync with the server is going when that's worth saying. */
@Composable
private fun PlaceHeader(place: Place, sync: SyncStatus) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      place.title,
      color = Placeholder.text,
      fontSize = 22.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("place:${place.name}").semantics { heading() },
    )
    Spacer(Modifier.weight(1f))
    syncLabel(sync)?.let {
      Text(
        it,
        color = Placeholder.mutedText,
        fontSize = 14.sp,
        modifier = Modifier.testTag("sync:status").semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}

/** What the header says about the sync, or null when there's nothing to say. */
internal fun syncLabel(sync: SyncStatus): String? =
  when (sync) {
    SyncStatus.FirstSync,
    SyncStatus.Syncing -> "Syncing"
    SyncStatus.Failed -> "Offline"
    SyncStatus.Unauthorized -> "RomM refused the sign-in · Settings → RomM"
    SyncStatus.NotStarted,
    SyncStatus.Synced -> null
  }

/**
 * A button in a menu.
 *
 * @param enabled whether pressing it does something now. Accessibility services say when it
 *   doesn't; it still takes focus.
 */
@Composable
internal fun MenuButton(
  key: String,
  label: String,
  enabled: Boolean = true,
  onConfirm: () -> Unit,
) {
  Box(
    Modifier.then(if (enabled) Modifier else Modifier.semantics { disabled() })
      .focusItem(key, onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}
