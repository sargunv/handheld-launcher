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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import com.composeunstyled.Text
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.downloads.DownloadsPlace
import dev.sargunv.handheld.ui.downloads.StorageSummary
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.GameScreen
import dev.sargunv.handheld.ui.game.GameSecondary
import dev.sargunv.handheld.ui.game.OpenWithScreen
import dev.sargunv.handheld.ui.game.OpenWithSecondary
import dev.sargunv.handheld.ui.home.HomePlace
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.library.DeviceState
import dev.sargunv.handheld.ui.library.LibraryPlace
import dev.sargunv.handheld.ui.library.LibrarySecondary
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator
import io.ktor.client.engine.HttpClientEngine

/** The top-level places, switched with L and R. */
enum class Place(val title: String) {
  Home("Home"),
  Library("Library"),
  Downloads("Downloads"),
}

/** The screens of the signed-in app. */
sealed interface Screen {
  /** The top-level places. */
  data object Places : Screen

  /** A game. */
  data class Game(val romId: Long, val title: String) : Screen

  /** The app a game opens in, from the game's menu. */
  data class OpenWith(val romId: Long, val title: String) : Screen
}

/** How the library's latest sync with the server went. */
enum class SyncStatus {
  NotStarted,
  /** Syncing into an empty cache, which fills in a piece at a time. */
  FirstSync,
  Syncing,
  Synced,
  Failed,
}

/** What the lower screen describes: the item focused on the upper one. */
@Immutable
sealed interface Focused {
  data class OnGame(val rom: Rom) : Focused

  data class OnCollection(val collection: Collection) : Focused
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
) {
  var settings: Settings by mutableStateOf(settings)
  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)
  var sync: SyncStatus by mutableStateOf(SyncStatus.NotStarted)
  val libraryView = LibraryState()

  /**
   * The games on the device or on their way, by ROM id. A game missing here is only on the server.
   */
  var deviceStates: Map<Long, DeviceState> by mutableStateOf(emptyMap())

  /** Counts the plays that may have synced saves, so the save history loads again after each. */
  var savesChanged: Int by mutableIntStateOf(0)

  val inputSettings: InputSettings
    get() = settings.input

  /** Moves [step] places along, stopping at the ends. */
  fun switchPlace(step: Int) {
    val next = Place.entries.getOrNull(place.ordinal + step) ?: return
    place = next
    focused = null
  }

  fun swapConfirmButton() {
    val swapped =
      when (inputSettings.confirmButton) {
        ConfirmButton.South -> ConfirmButton.East
        ConfirmButton.East -> ConfirmButton.South
      }
    settings = settings.copy(input = inputSettings.copy(confirmButton = swapped))
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
    if (state.place == Place.Downloads && state.navigator.current == Screen.Places) {
      StorageSummary(state.downloads)
      return@Column
    }
    val screen = state.navigator.current
    if (screen is Screen.Game) {
      GameSecondary(state, screen)
      return@Column
    }
    if (screen is Screen.OpenWith) {
      OpenWithSecondary(state, screen)
      return@Column
    }
    val focused = state.focused
    when {
      state.place == Place.Library && state.navigator.current == Screen.Places ->
        LibrarySecondary(state)
      focused is Focused.OnGame -> GameDescription(state, focused.rom)
      focused is Focused.OnCollection -> {
        Text(focused.collection.name, color = Placeholder.text, fontSize = 24.sp)
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
    Cover(rom.coverLargeUrl ?: rom.coverSmallUrl, rom.title, Modifier.size(180.dp, 240.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(rom.title, color = Placeholder.text, fontSize = 24.sp)
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

@Composable
private fun PlacesScreen(state: AppState) {
  val places = rememberSaveableStateHolder()
  var quickMenu by rememberSaveable { mutableStateOf(false) }
  Box(
    Modifier.fillMaxSize().onAction { action ->
      when (action) {
        Action.PreviousShelf -> state.switchPlace(-1)
        Action.NextShelf -> state.switchPlace(1)
        Action.QuickMenu -> quickMenu = true
        else -> return@onAction false
      }
      true
    },
  ) {
    Column(Modifier.fillMaxSize()) {
      // Inside a shelf of Library, L and R step between shelves, so the tabs don't name them.
      val queue by state.downloads.queue.collectAsState()
      PlaceTabs(
        state.place,
        state.sync,
        queue.size,
        state.inputSettings,
        showButtons = !(state.place == Place.Library && state.libraryView.isOpen),
      )
      Box(Modifier.fillMaxWidth().weight(1f)) {
        // Each place is a focus scope, which keeps its focus and scroll position while another
        // place shows.
        places.SaveableStateProvider(state.place.name) {
          when (state.place) {
            Place.Home -> HomePlace(state)
            Place.Library -> LibraryPlace(state)
            Place.Downloads -> DownloadsPlace(state.downloads)
          }
        }
      }
    }
    if (quickMenu) QuickMenu(state, onDismissRequest = { quickMenu = false })
  }
}

@Composable
private fun PlaceTabs(
  current: Place,
  sync: SyncStatus,
  downloads: Int,
  settings: InputSettings,
  showButtons: Boolean,
) {
  fun buttonFor(action: Action) =
    if (showButtons) {
      PadButton.entries.firstOrNull { settings.actionFor(it) == action }?.label.orEmpty()
    } else ""

  Row(
    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(buttonFor(Action.PreviousShelf), color = Placeholder.mutedText, fontSize = 14.sp)
    for (place in Place.entries) {
      Text(
        if (place == Place.Downloads && downloads > 0) "${place.title} $downloads" else place.title,
        color = if (place == current) Placeholder.text else Placeholder.mutedText,
        fontWeight = if (place == current) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.testTag("place:${place.name}"),
      )
    }
    Text(buttonFor(Action.NextShelf), color = Placeholder.mutedText, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    val status =
      when (sync) {
        SyncStatus.FirstSync,
        SyncStatus.Syncing -> "Syncing"
        SyncStatus.Failed -> "Offline"
        SyncStatus.NotStarted,
        SyncStatus.Synced -> null
      }
    status?.let { Text(it, color = Placeholder.mutedText, fontSize = 14.sp) }
  }
}

/** A place that has nothing to show yet. */
@Composable
private fun EmptyPlace(text: String) {
  FocusScope(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(text, color = Placeholder.mutedText)
      }
      HintBar(listOf(Hint(Action.QuickMenu, "Quick menu")))
    }
  }
}

/** The global settings, on the quick menu button. */
@Composable
private fun QuickMenu(state: AppState, onDismissRequest: () -> Unit) {
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Quick menu", color = Placeholder.text)
      val confirm =
        when (state.inputSettings.confirmButton) {
          ConfirmButton.South -> PadButton.South
          ConfirmButton.East -> PadButton.East
        }
      MenuButton("quick:confirm", "Confirm with ${confirm.label}") { state.swapConfirmButton() }
    }
  }
}

/** A button in a menu. */
@Composable
internal fun MenuButton(key: String, label: String, onConfirm: () -> Unit) {
  Box(
    Modifier.testTag(key)
      .focusItem(key, onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}
