package dev.sargunv.handheld.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.cover.Cover
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusLazyRow
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.game.OpenWithScope
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.library.DeviceState
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.library.collectionShelfKey
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.SyncStatus
import dev.sargunv.handheld.ui.places.coverDescription
import dev.sargunv.handheld.ui.places.gameCount

/** How many games a shelf of games holds. */
private const val SHELF_SIZE = 20

/**
 * Home: the game the user was last playing, focused first, then the shelves in the order the user
 * set. It shows what the library cache holds, and updates as syncs change it.
 *
 * Confirm on the game being played plays it, and its menu goes to its page. A collection opens its
 * games in the Library, and Back there comes back here.
 */
@Composable
internal fun HomePlace(state: AppState) {
  val library = state.library
  // The first is the game being played, and the rest make the shelf of recent games.
  val played by remember(library) { library.recentlyPlayed(SHELF_SIZE) }.collectAsState(null)
  val added by remember(library) { library.recentlyAdded(SHELF_SIZE) }.collectAsState(null)
  val collections by remember(library) { library.collections() }.collectAsState(null)
  var menuOpen by rememberSaveable { mutableStateOf(false) }
  var gameMenuOpen by rememberSaveable { mutableStateOf(false) }
  var focusedShelf by rememberSaveable { mutableStateOf<HomeShelf?>(null) }
  // Whether the game being played has focus, rather than something on a shelf.
  var focusedContinue by remember { mutableStateOf(false) }

  val settings = state.settings.home
  val playedNow = played.orEmpty()
  val addedNow = added.orEmpty()
  val collectionsNow = collections.orEmpty()
  val shown =
    settings.visible.filter { shelf ->
      when (shelf) {
        HomeShelf.RecentlyPlayed -> playedNow.size > 1
        HomeShelf.Collections -> collectionsNow.isNotEmpty()
        HomeShelf.RecentlyAdded -> addedNow.isNotEmpty()
      }
    }
  // The first sync fills the cache a piece at a time, so Home waits for all of it. That way the
  // game the user was last playing is there to take focus first.
  val syncing = addedNow.isEmpty() || state.sync == SyncStatus.FirstSync
  val hasItems = !syncing && (playedNow.isNotEmpty() || shown.isNotEmpty())
  val menuShelf = focusedShelf?.takeIf { hasItems && it in shown }
  val hasMenu = !syncing && (menuShelf != null || settings.hidden.isNotEmpty())

  val continueRom = playedNow.firstOrNull()
  // The game being played plays at once when it's on the device; otherwise its page opens, which
  // the Game screen decides once it knows.
  val continuePlays =
    continueRom != null && state.deviceStates[continueRom.id] == DeviceState.OnDevice

  fun openGame(rom: Rom) = state.navigator.push(Screen.Game(rom.id, rom.title))
  fun playGame(rom: Rom) = state.navigator.push(Screen.Game(rom.id, rom.title, play = true))
  fun openCollection(collection: Collection) =
    state.openShelf(
      LibraryTab.Collections,
      collectionShelfKey(collection.kind, collection.id),
      fromHome = true,
    )
  fun onFocusGame(rom: Rom) {
    focusedContinue = false
    state.focused = Focused.OnGame(rom)
  }
  fun onFocusContinue(rom: Rom) {
    focusedContinue = true
    state.focused = Focused.OnGame(rom)
  }
  fun onFocusCollection(collection: Collection) {
    focusedContinue = false
    state.focused = Focused.OnCollection(collection)
  }

  // The lower screen describes nothing while Home has nothing to focus.
  LaunchedEffect(hasItems) { if (!hasItems) state.focused = null }

  // Home is a focus scope of its own, so its menu opens even while it has nothing to focus. A new
  // game to continue starts it over with focus on that game, even when the play history lands
  // after the shelves did.
  val continueKey = playedNow.firstOrNull()?.let { "continue:${it.id}" }
  key(continueKey) {
    FocusScope(
      Modifier.fillMaxSize().onAction { action ->
        when {
          action != Action.Menu -> return@onAction false
          hasItems && focusedContinue && continueRom != null -> gameMenuOpen = true
          hasMenu -> menuOpen = true
          else -> return@onAction false
        }
        true
      },
      initialFocus = continueKey,
    ) {
      // The cache answers within a frame or two; until then there's nothing to show.
      if (played == null || added == null || collections == null) return@FocusScope
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
          val message =
            when {
              !syncing && !hasItems -> "No shelves to show. The menu shows hidden shelves again."
              !syncing -> null
              state.sync == SyncStatus.Failed -> "Couldn't reach your server to sync your library."
              state.sync == SyncStatus.Synced -> "Your library has no games yet."
              else -> "Syncing your library"
            }
          if (message != null) {
            Text(
              message,
              color = Placeholder.mutedText,
              modifier = Modifier.align(Alignment.Center),
            )
          } else {
            FocusLazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
              verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
              playedNow.firstOrNull()?.let { rom ->
                item(key = "continue") {
                  ShelfColumn(
                    title = "Continue playing",
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedShelf = null },
                  ) {
                    GameTile(
                      rom = rom,
                      deviceState = state.deviceStates[rom.id],
                      key = "continue:${rom.id}",
                      modifier = Modifier.padding(start = 48.dp).size(180.dp, 240.dp),
                      onFocus = ::onFocusContinue,
                      onOpen = ::playGame,
                    )
                  }
                }
              }
              for (shelf in shown) {
                item(key = shelf.name) {
                  val onFocusShelf = { focusedShelf = shelf }
                  when (shelf) {
                    HomeShelf.RecentlyPlayed ->
                      GameShelf(
                        shelf,
                        playedNow.drop(1),
                        state.deviceStates,
                        onFocusShelf,
                        ::onFocusGame,
                        ::openGame,
                      )
                    HomeShelf.RecentlyAdded ->
                      GameShelf(
                        shelf,
                        addedNow,
                        state.deviceStates,
                        onFocusShelf,
                        ::onFocusGame,
                        ::openGame,
                      )
                    HomeShelf.Collections ->
                      CollectionShelf(
                        collectionsNow,
                        onFocusShelf,
                        ::onFocusCollection,
                        ::openCollection,
                      )
                  }
                }
              }
            }
          }
        }
        HintBar(
          buildList {
            if (hasItems) {
              add(Hint(Action.Confirm, if (focusedContinue && continuePlays) "Play" else "Open"))
            }
            if (hasMenu || (hasItems && focusedContinue)) add(Hint(Action.Menu, "Menu"))
            add(Hint(Action.MainMenu, "Main menu"))
          },
        )
      }
      if (gameMenuOpen && continueRom != null) {
        GameMenu(
          state = state,
          rom = continueRom,
          settings = settings,
          onShowShelf = { state.settings = state.settings.copy(home = settings.show(it)) },
          onDismissRequest = { gameMenuOpen = false },
        )
      }
      if (menuOpen) {
        ShelfMenu(
          settings = settings,
          shelf = menuShelf,
          shown = shown,
          onChange = { state.settings = state.settings.copy(home = it) },
          onDismissRequest = { menuOpen = false },
        )
      }
    }
  }
}

@Composable
private fun ShelfColumn(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      title,
      color = Placeholder.mutedText,
      modifier = Modifier.padding(start = 48.dp).semantics { heading() },
    )
    content()
  }
}

@Composable
private fun GameShelf(
  shelf: HomeShelf,
  games: List<Rom>,
  deviceStates: Map<Long, DeviceState>,
  onFocusShelf: () -> Unit,
  onFocusGame: (Rom) -> Unit,
  onOpen: (Rom) -> Unit,
) {
  ShelfColumn(
    shelf.title,
    Modifier.onFocusChanged { if (it.hasFocus) onFocusShelf() },
  ) {
    FocusLazyRow(
      modifier = Modifier.testTag("shelf:${shelf.name}"),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(games, key = { it.id }) { rom ->
        GameTile(
          rom = rom,
          deviceState = deviceStates[rom.id],
          key = "${shelf.name}:${rom.id}",
          modifier = Modifier.size(120.dp, 160.dp),
          onFocus = onFocusGame,
          onOpen = onOpen,
        )
      }
    }
  }
}

@Composable
private fun GameTile(
  rom: Rom,
  deviceState: DeviceState?,
  key: String,
  modifier: Modifier,
  onFocus: (Rom) -> Unit,
  onOpen: (Rom) -> Unit,
) {
  Cover(
    url = rom.coverSmallUrl,
    title = rom.title,
    modifier =
      modifier
        .onFocusChanged { if (it.isFocused) onFocus(rom) }
        .focusItem(
          key,
          onConfirm = { onOpen(rom) },
          description = coverDescription(rom, deviceState),
        ),
  )
}

@Composable
private fun CollectionShelf(
  collections: List<Collection>,
  onFocusShelf: () -> Unit,
  onFocusCollection: (Collection) -> Unit,
  onOpen: (Collection) -> Unit,
) {
  ShelfColumn(
    HomeShelf.Collections.title,
    Modifier.onFocusChanged { if (it.hasFocus) onFocusShelf() },
  ) {
    FocusLazyRow(
      modifier = Modifier.testTag("shelf:${HomeShelf.Collections.name}"),
      contentPadding = PaddingValues(horizontal = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(collections, key = { "${it.kind}:${it.id}" }) { collection ->
        val key = "collection:${collection.kind}:${collection.id}"
        Column(
          Modifier.onFocusChanged { if (it.isFocused) onFocusCollection(collection) }
            .focusItem(
              key,
              onConfirm = { onOpen(collection) },
              description = "${collection.name}, ${gameCount(collection.romCount)}",
            )
            .size(200.dp, 100.dp)
            .background(Placeholder.tile)
            .padding(12.dp),
          verticalArrangement = Arrangement.Bottom,
        ) {
          // The tile's description says all of this once.
          Column(Modifier.clearAndSetSemantics {}) {
            Text(collection.name, color = Placeholder.text)
            Text(gameCount(collection.romCount), color = Placeholder.mutedText)
          }
        }
      }
    }
  }
}

/**
 * The menu on the game being played: its page and Open with, and the hidden shelves, which this
 * menu shows again too, since the game may be all that Home shows. Each choice closes the menu.
 */
@Composable
private fun GameMenu(
  state: AppState,
  rom: Rom,
  settings: HomeSettings,
  onShowShelf: (HomeShelf) -> Unit,
  onDismissRequest: () -> Unit,
) {
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(rom.title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      MenuButton("menu:game", "Go to game page") {
        onDismissRequest()
        state.navigator.push(Screen.Game(rom.id, rom.title))
      }
      MenuButton("menu:open-with", "Open with") {
        onDismissRequest()
        state.navigator.push(Screen.OpenWith(OpenWithScope.Game(rom.id), rom.title))
      }
      for (hidden in HomeShelf.entries.filter { it in settings.hidden }) {
        MenuButton("menu:show:${hidden.name}", "Show “${hidden.title}”") {
          onShowShelf(hidden)
          onDismissRequest()
        }
      }
    }
  }
}

/**
 * The menu on Home: it hides or moves the focused [shelf], and shows the hidden shelves again. Each
 * choice closes the menu.
 */
@Composable
private fun ShelfMenu(
  settings: HomeSettings,
  shelf: HomeShelf?,
  shown: List<HomeShelf>,
  onChange: (HomeSettings) -> Unit,
  onDismissRequest: () -> Unit,
) {
  fun choose(changed: HomeSettings) {
    onChange(changed)
    onDismissRequest()
  }
  Dialog(onDismissRequest, Modifier.background(Placeholder.dialog).padding(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        shelf?.title ?: "Home",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      if (shelf != null) {
        MenuButton("menu:hide", "Hide this shelf") { choose(settings.hide(shelf)) }
        if (shown.firstOrNull() != shelf) {
          MenuButton("menu:up", "Move up") { choose(settings.moveUp(shelf, shown)) }
        }
        if (shown.lastOrNull() != shelf) {
          MenuButton("menu:down", "Move down") { choose(settings.moveDown(shelf, shown)) }
        }
      }
      for (hidden in HomeShelf.entries.filter { it in settings.hidden }) {
        MenuButton("menu:show:${hidden.name}", "Show “${hidden.title}”") {
          choose(settings.show(hidden))
        }
      }
    }
  }
}
