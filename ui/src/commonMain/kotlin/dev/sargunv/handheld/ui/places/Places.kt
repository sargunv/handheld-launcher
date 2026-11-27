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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.data.romm.SyncStatus
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.about.AboutScreen
import dev.sargunv.handheld.ui.about.AboutSecondary
import dev.sargunv.handheld.ui.about.LicenceScreen
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.downloads.DownloadsPlace
import dev.sargunv.handheld.ui.downloads.StorageSummary
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.GameScreen
import dev.sargunv.handheld.ui.game.GameSecondary
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.game.OpenWithScreen
import dev.sargunv.handheld.ui.game.OpenWithSecondary
import dev.sargunv.handheld.ui.home.HomePlace
import dev.sargunv.handheld.ui.library.LibraryPlace
import dev.sargunv.handheld.ui.library.LibrarySecondary
import dev.sargunv.handheld.ui.library.progress
import dev.sargunv.handheld.ui.library.statusOf
import dev.sargunv.handheld.ui.navigation.BackHandler
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.settings.SettingsPage
import dev.sargunv.handheld.ui.settings.SettingsSecondary
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/** The top-level pages, which the main menu goes to. */
enum class Place(val title: String) {
  Home("Home"),
  Library("Library"),
  Downloads("Downloads"),
  Settings("Settings"),
}

/** The signed-in app's top screen. */
@Composable
fun MainPrimary(state: AppState) {
  // Each place is a focus scope, which keeps its focus and scroll positions while another place,
  // or a screen over the places, shows. Each start over on Home gives Home new ones.
  val places = rememberSaveableStateHolder()
  var homeStart by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(state, places) {
    state.homeStarts.collect {
      places.removeState(homeKey(homeStart))
      homeStart++
      state.navigator.backToStart()
      state.showPlace(Place.Home)
    }
  }
  LaunchedEffect(state) { state.followAdoptions() }
  NavigationHost(state.navigator, Modifier.fillMaxSize().background(Placeholder.background)) {
    screen ->
    when (screen) {
      Screen.Places -> NavEntry(screen) { PlacesScreen(state, places, homeKey(homeStart)) }
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
    when (val screen = state.navigator.current) {
      is Screen.Game -> GameSecondary(state, screen)
      is Screen.OpenWith -> OpenWithSecondary(state, screen)
      Screen.About,
      is Screen.Licence -> AboutSecondary(state, screen)
      Screen.Places -> {
        val focused = state.focused
        when {
          state.place == Place.Downloads -> StorageSummary(state.downloads)
          state.place == Place.Library -> LibrarySecondary(state)
          state.place == Place.Settings -> SettingsSecondary(state, signedIn = true)
          focused is Focused.OnGame -> GameDescription(state, focused.game)
          focused is Focused.OnCollection -> {
            val collection = focused.collection
            ShelfDescription(collection.name, collection.romCount, null, collection.description)
          }
        }
      }
    }
  }
}

/**
 * Puts the ROM's screens in place of a local game's in the back stack once the adopt step has
 * handed what was kept for the file to the ROM. It runs until cancelled.
 */
private suspend fun AppState.followAdoptions() {
  val backStack = snapshotFlow { navigator.backStack.toList() }
  combine(handedOver, backStack) { handedOver, screens ->
      val roms = handedOver.associate { it.local to it.rom }
      for (screen in screens) {
        val replacement =
          when (screen) {
            is Screen.Game ->
              roms[screen.key]?.let { library.rom(it.romId) }?.let { Screen.Game(it.id, it.title) }
            is Screen.OpenWith ->
              (screen.scope as? OpenWithScope.Game)
                ?.let { roms[it.game] }
                ?.let { screen.copy(scope = OpenWithScope.Game(it)) }
            else -> null
          }
        if (replacement != null) navigator.replace(screen, replacement)
      }
    }
    .collect()
}

/**
 * The lower screen's description of a shelf of games: its [title], how many [games] it holds and
 * how many are [onDevice] when that's known, and what it's [about].
 */
@Composable
internal fun ShelfDescription(title: String, games: Int, onDevice: Int?, about: String?) {
  Text(
    title,
    color = Placeholder.text,
    fontSize = 24.sp,
    modifier = Modifier.semantics { heading() },
  )
  Text(gameCount(games), color = Placeholder.mutedText)
  if (onDevice != null) Text(onDeviceCount(onDevice), color = Placeholder.mutedText)
  if (!about.isNullOrBlank()) Text(about, color = Placeholder.mutedText)
}

/** The lower screen's description of a game. */
@Composable
internal fun GameDescription(state: AppState, game: LibraryGame) {
  val library = state.library
  val platforms by remember(library) { library.platforms() }.collectAsState(emptyList())
  val statuses by
    remember(state.downloads) { state.downloads.statuses() }.collectAsState(emptyMap())
  Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Cover(
      (game as? Rom)?.let { it.coverLargeUrl ?: it.coverSmallUrl },
      game.title,
      Modifier.size(180.dp, 240.dp),
      description = null,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        game.title,
        color = Placeholder.text,
        fontSize = 24.sp,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        platforms.find { it.id == game.platformId }?.name ?: game.platformSlug,
        color = Placeholder.mutedText,
      )
      when (game) {
        is Rom -> {
          val onDevice =
            when (statusOf(game, statuses)) {
              is RomStatus.OnDevice -> "On this device"
              RomStatus.Queued,
              is RomStatus.Downloading -> "Downloading"
              is RomStatus.Failed,
              RomStatus.NotOnDevice -> "Not on this device"
            }
          Text(onDevice, color = Placeholder.mutedText)
        }
        is LocalGame -> {
          Text("On this device", color = Placeholder.mutedText)
          Text(
            NOT_ON_SERVER.replaceFirstChar { it.uppercase() },
            color = Placeholder.mutedText,
            modifier = Modifier.testTag("game:not-on-server"),
          )
          Text(
            "${game.fileName} · ${formatSize(game.sizeBytes)}",
            color = Placeholder.mutedText,
          )
        }
      }
    }
  }
}

private fun homeKey(start: Int) = "${Place.Home.name}:$start"

/** What a local game is marked with, wherever it shows. */
internal const val NOT_ON_SERVER = "not on your server"

internal fun gameCount(count: Int) = if (count == 1) "1 game" else "$count games"

internal fun onDeviceCount(count: Int) = "$count on this device"

/**
 * What accessibility services say for a game's cover: its title, and whether it's on the device,
 * which the cover doesn't show.
 */
internal fun coverDescription(rom: Rom, status: RomStatus): String {
  val where =
    when (status) {
      is RomStatus.OnDevice -> "on this device"
      RomStatus.Queued -> "downloading"
      is RomStatus.Downloading ->
        status.progress?.let { "downloading ${(it * 100).toInt()}%" } ?: "downloading"
      is RomStatus.Failed,
      RomStatus.NotOnDevice -> "not on this device"
    }
  return "${rom.title}, $where"
}

/**
 * The top-level places, one at a time under a header that names it. Back on any place but Home goes
 * to Home; on Home it does nothing.
 */
@Composable
private fun PlacesScreen(state: AppState, places: SaveableStateHolder, homeKey: String) {
  BackHandler(
    onBack = { state.showPlace(Place.Home) },
    Modifier.fillMaxSize(),
    enabled = state.place != Place.Home,
  ) {
    Column(Modifier.fillMaxSize()) {
      PlaceHeader(state.place, state.romm.sync.collectAsState().value)
      Box(Modifier.fillMaxWidth().weight(1f)) {
        places.SaveableStateProvider(if (state.place == Place.Home) homeKey else state.place.name) {
          when (state.place) {
            Place.Home -> HomePlace(state)
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
    if (sync != SyncStatus.NotStarted && sync != SyncStatus.Synced) {
      Text(
        sync.label,
        color = Placeholder.mutedText,
        fontSize = 14.sp,
        modifier = Modifier.testTag("sync:status").semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
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
      .focusItem(key, onConfirm = onConfirm.takeIf { enabled }, role = Role.Button)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}
