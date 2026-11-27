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
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.romm.SyncStatus
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
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.library.OpenedFrom
import dev.sargunv.handheld.ui.library.collectionShelfKey
import dev.sargunv.handheld.ui.library.statusOf
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.places.Focused
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.places.Screen
import dev.sargunv.handheld.ui.places.coverDescription
import dev.sargunv.handheld.ui.places.describes
import dev.sargunv.handheld.ui.places.emptyLabel
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
  val sync by state.romm.sync.collectAsState()
  // The first is the game being played, and the rest make the shelf of recent games.
  val played by remember(library) { library.recentlyPlayed(SHELF_SIZE) }.collectAsState(null)
  val added by remember(library) { library.recentlyAdded(SHELF_SIZE) }.collectAsState(null)
  val collections by remember(library) { library.collections() }.collectAsState(null)
  val statuses by
    remember(state.downloads) { state.downloads.statuses() }.collectAsState(emptyMap())
  var menuOpen by remember { mutableStateOf(false) }
  var gameMenuOpen by remember { mutableStateOf(false) }
  // The shelf with focus, or null while the game being played has it.
  var focusedShelf by rememberSaveable { mutableStateOf<HomeShelf?>(null) }

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
  val syncing = addedNow.isEmpty() || sync == SyncStatus.FirstSync
  val hasItems = !syncing && (playedNow.isNotEmpty() || shown.isNotEmpty())
  val menuShelf = focusedShelf?.takeIf { hasItems && it in shown }
  val hasMenu = !syncing && (menuShelf != null || settings.hidden.isNotEmpty())

  val continueRom = playedNow.firstOrNull()
  // The game being played plays at once when it's on the device; otherwise its page opens, which
  // the Game screen decides once it knows.
  val continuePlays = continueRom != null && statuses[continueRom.id] is RomStatus.OnDevice
  val continueFocused = hasItems && continueRom != null && focusedShelf == null

  fun openGame(rom: Rom) = state.navigator.push(Screen.Game(rom.id, rom.title))
  fun playGame(rom: Rom) = state.navigator.push(Screen.Game(rom.id, rom.title, play = true))
  fun openCollection(collection: Collection) =
    state.openShelf(
      LibraryTab.Collections,
      collectionShelfKey(collection.kind, collection.id),
      OpenedFrom.Home,
    )

  // Home is a focus scope of its own, so its menu opens even while it has nothing to focus. A new
  // game to continue starts it over with focus on that game, even when the play history lands
  // after the shelves did.
  val continueKey = playedNow.firstOrNull()?.let { "continue:${it.id}" }
  key(continueKey) {
    FocusScope(
      Modifier.fillMaxSize().onAction { action ->
        when {
          action != Action.Menu -> return@onAction false
          continueFocused -> gameMenuOpen = true
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
              !syncing && !hasItems -> "No shelves to show"
              !syncing -> null
              else -> sync.emptyLabel("No games")
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
                      state = state,
                      rom = rom,
                      status = statusOf(rom, statuses),
                      key = "continue:${rom.id}",
                      modifier = Modifier.padding(start = 48.dp).size(180.dp, 240.dp),
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
                        state,
                        shelf,
                        playedNow.drop(1),
                        statuses,
                        onFocusShelf,
                        ::openGame,
                      )
                    HomeShelf.RecentlyAdded ->
                      GameShelf(
                        state,
                        shelf,
                        addedNow,
                        statuses,
                        onFocusShelf,
                        ::openGame,
                      )
                    HomeShelf.Collections ->
                      CollectionShelf(state, collectionsNow, onFocusShelf, ::openCollection)
                  }
                }
              }
            }
          }
        }
        HintBar(
          buildList {
            if (hasItems) {
              add(Hint(Action.Confirm, if (continueFocused && continuePlays) "Play" else "Open"))
            }
            if (hasMenu || continueFocused) add(Hint(Action.Menu, "Menu"))
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
  state: AppState,
  shelf: HomeShelf,
  games: List<Rom>,
  statuses: Map<Long, RomStatus>,
  onFocusShelf: () -> Unit,
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
          state = state,
          rom = rom,
          status = statusOf(rom, statuses),
          key = "${shelf.name}:${rom.id}",
          modifier = Modifier.size(120.dp, 160.dp),
          onOpen = onOpen,
        )
      }
    }
  }
}

@Composable
private fun GameTile(
  state: AppState,
  rom: Rom,
  status: RomStatus,
  key: String,
  modifier: Modifier,
  onOpen: (Rom) -> Unit,
) {
  Cover(
    url = rom.coverSmallUrl,
    title = rom.title,
    modifier =
      modifier
        .describes(state, Focused.OnGame(rom))
        .focusItem(
          key,
          onConfirm = { onOpen(rom) },
          description = coverDescription(rom, status),
        ),
  )
}

@Composable
private fun CollectionShelf(
  state: AppState,
  collections: List<Collection>,
  onFocusShelf: () -> Unit,
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
          Modifier.describes(state, Focused.OnCollection(collection))
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
  Dialog(onDismissRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(rom.title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      MenuButton("menu:game", "Go to game page") {
        onDismissRequest()
        state.navigator.push(Screen.Game(rom.id, rom.title))
      }
      MenuButton("menu:open-with", "Open with") {
        onDismissRequest()
        state.navigator.push(Screen.OpenWith(OpenWithScope.Game(rom.key), rom.title))
      }
      ShowShelfButtons(settings) {
        onShowShelf(it)
        onDismissRequest()
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
  Dialog(onDismissRequest) {
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
      ShowShelfButtons(settings) { choose(settings.show(it)) }
    }
  }
}

/** A button for each shelf [settings] hide, which shows it again. */
@Composable
private fun ShowShelfButtons(settings: HomeSettings, onShow: (HomeShelf) -> Unit) {
  for (hidden in HomeShelf.entries.filter { it in settings.hidden }) {
    MenuButton("menu:show:${hidden.name}", "Show “${hidden.title}”") { onShow(hidden) }
  }
}
