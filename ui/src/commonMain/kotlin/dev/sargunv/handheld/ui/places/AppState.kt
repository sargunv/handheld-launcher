package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders
import dev.sargunv.handheld.ui.library.DeviceState
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.library.OpenedFrom
import dev.sargunv.handheld.ui.library.deviceStatesOf
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.settings.SettingsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** State the app's two screens share. */
@Stable
class AppState(
  settings: Settings,
  /** The sign-in to the RomM server, its client and the library's sync. */
  val romm: RommSession,
  val library: LibraryCache,
  val downloads: DownloadManager,
  val games: DeviceGames,
  val launcher: GameLauncher,
  val apps: AppCatalog,
  device: DeviceDescription,
  /** Shares the state's flows while the screens collect them. */
  scope: CoroutineScope,
  /** The RetroAchievements account, on platforms whose games can earn achievements. */
  val retroAchievements: RetroAchievementsState? = null,
  /** What About says about this build. */
  val appInfo: AppInfo = AppInfo.Unknown,
  /** Reads what a game's file says about itself. */
  val romHeaders: RomHeaders = RomHeaders.None,
  /**
   * What the platform keeps per game besides the settings, such as the built-in session's saves.
   */
  platformState: PerGameState = PerGameState.None,
) {
  var settings: Settings by mutableStateOf(settings)

  /** Everything kept per game: the settings, and what the platform keeps. */
  private val gameState: PerGameState =
    PerGameState.of(
      PerGameState { this.settings = this.settings.adopted(it) },
      platformState,
    )

  /** The game [play] is playing, whose state stays where it is until the game is over. */
  var playing: GameKey? by mutableStateOf(null)
    private set

  /** Held while adopting and while [play] sets [playing], so nothing adopts the game it plays. */
  private val adoption = Mutex()

  /** The adoptions still listed that [adopt] has handed over. */
  private var handedOver = emptySet<Adoption>()

  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)

  /**
   * The games on the device or on their way, by ROM id. A game missing here is only on the server.
   */
  val deviceStates: StateFlow<Map<Long, DeviceState>> =
    downloads
      .statuses()
      .map(::deviceStatesOf)
      .stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

  /** The local games: files on the device that match none of the server's games. */
  val localGames: StateFlow<List<LocalGame>> =
    games.localGames().stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

  val libraryView = LibraryState(library, deviceStates, localGames) { libraryTab }
  val settingsView = SettingsView()

  val account = Account(romm, device)

  private val homeStartRequests = Channel<Unit>(Channel.CONFLATED)

  /** The requests to start over on Home, which [MainPrimary] carries out. */
  internal val homeStarts: Flow<Unit> = homeStartRequests.receiveAsFlow()

  /** The Library tab that shows, which stays between launches. */
  var libraryTab: LibraryTab
    get() = settings.library.tab
    set(value) {
      if (value != libraryTab)
        settings = settings.copy(library = settings.library.copy(tab = value))
    }

  /**
   * Shows [next]. Leaving the Library closes a shelf that Home opened, since Back on it goes Home.
   */
  fun showPlace(next: Place) {
    if (next == place) return
    if (place == Place.Library && libraryView.openedFrom == OpenedFrom.Home) libraryView.close()
    place = next
  }

  /**
   * Opens the games of the shelf with [shelfKey] on Library's [tab], as if the player had opened it
   * there. Back on it returns to where it was opened [from].
   */
  fun openShelf(tab: LibraryTab, shelfKey: String, from: OpenedFrom = OpenedFrom.Tab) {
    showPlace(Place.Library)
    libraryTab = tab
    libraryView.open(tab, shelfKey, from)
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
    homeStartRequests.trySend(Unit)
  }

  /**
   * Signs out of the server and goes to the sign-in, which offers the same server again. The games
   * on the device, their saves and the library cache stay. The downloads not yet done leave the
   * queue, since they're games of this server, unless [keepQueue], for signing in again to the same
   * one: they wait for the sign-in, and a sign-in to another server drops them then. The next
   * sign-in opens on Home. It asks for permission to upload games when [allowUploads].
   *
   * Once begun, it finishes even if the screen that asked goes away.
   */
  suspend fun signOut(allowUploads: Boolean = false, keepQueue: Boolean = false) =
    withContext(NonCancellable) {
      // Before the credentials go, so the server hears of the canceled installs it asked for.
      if (!keepQueue) for (download in downloads.queue.value) downloads.cancel(download.romId)
      account.signOut(allowUploads)
      navigator.backToStart()
      place = Place.Home
    }

  /**
   * The game [key] names: a ROM from the library, or a local game once the scan of the platforms'
   * folders has found it. Null when there's no such game, such as a file that's gone or now matches
   * a ROM.
   */
  suspend fun game(key: GameKey): LibraryGame? =
    when (key) {
      is GameKey.Romm -> library.rom(key.romId)
      is GameKey.Local ->
        (games.localGame(key.path).first { it != null } as? LocalGameState.Local)?.game
    }

  /**
   * Runs [launch], the launch of [game], once any file the latest scan matched to [game] has handed
   * it what was kept for the file. What's kept for [game] stays where it is until [launch] returns.
   */
  suspend fun play(game: GameKey, launch: suspend () -> List<String>): List<String> {
    adoption.withLock {
      adopt(games.adoptions.first().filter { it.rom == game })
      playing = game
    }
    try {
      return launch()
    } finally {
      playing = null
    }
  }

  /**
   * The one adopt step: each file on the device that's a ROM's hands what's kept for it as a local
   * game to the ROM. The game [playing] holds waits until it's over. It runs until cancelled.
   */
  suspend fun adoptMatchedFiles() {
    combine(games.adoptions, snapshotFlow { playing }) { adoptions, _ -> adoptions }
      .collect { adoptions ->
        adoption.withLock {
          handedOver = handedOver intersect adoptions.toSet()
          adopt(adoptions.filterNot { playing == it.local || playing == it.rom })
        }
      }
  }

  /** Hands over what's kept for each of [pending] that isn't handed over yet, under [adoption]. */
  private suspend fun adopt(pending: List<Adoption>) {
    val new = pending - handedOver
    if (new.isEmpty()) return
    gameState.adopt(new)
    handedOver += new
  }
}
