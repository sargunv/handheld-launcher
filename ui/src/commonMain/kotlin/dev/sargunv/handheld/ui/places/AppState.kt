package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.library.OpenedFrom
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.settings.SettingsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** State the app's two screens share, over [data] and [platform]. */
@Stable
class AppState(
  settings: Settings,
  data: HandheldData,
  platform: AppPlatform,
  /** Runs the RetroAchievements sign-ins and sign-outs, so they finish when a screen goes away. */
  scope: CoroutineScope,
) {
  /** The sign-in to the RomM server, its client and the library's sync. */
  val romm: RommSession = data.romm
  val library: LibraryCache = data.library
  val downloads: DownloadManager = data.downloads
  val games: DeviceGames = data.games
  val launcher: GameLauncher = platform.launcher
  val apps: AppCatalog = platform.apps

  /** The RetroAchievements account, on platforms whose games can earn achievements. */
  val retroAchievements: RetroAchievementsState? =
    platform.retroAchievements?.let { RetroAchievementsState(it, scope) }

  /** What About says about this build. */
  val appInfo: AppInfo = platform.appInfo

  /** Reads what a game's file says about itself. */
  val romHeaders: RomHeaders = platform.romHeaders

  var settings: Settings by mutableStateOf(settings)

  /** Everything kept per game: the settings, and what the platform keeps. */
  private val gameState: PerGameState =
    PerGameState.of(
      PerGameState {
        this.settings = this.settings.adopted(it)
        emptyList()
      },
      platform.gameState,
    )

  /** The game [play] is playing, whose state stays where it is until the game is over. */
  var playing: GameKey? by mutableStateOf(null)
    private set

  /** Held while adopting and while [play] sets [playing], so nothing adopts the game it plays. */
  private val adoption = Mutex()

  private val handover = MutableStateFlow(emptySet<Adoption>())

  /** The adoptions still listed that the adopt step has handed over. */
  val handedOver: StateFlow<Set<Adoption>> = handover.asStateFlow()

  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)

  val libraryView = LibraryState(library, downloads.statuses(), games.localGames()) { libraryTab }
  val settingsView = SettingsView(romm, offersAchievements = retroAchievements != null)

  val account = Account(romm, platform.device)

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
          handover.update { it intersect adoptions.toSet() }
          adopt(adoptions.filterNot { playing == it.local || playing == it.rom })
        }
      }
  }

  /** Hands over what's kept for each of [pending] that isn't handed over yet, under [adoption]. */
  private suspend fun adopt(pending: List<Adoption>) {
    val new = pending - handover.value
    if (new.isEmpty()) return
    val unfinished = gameState.adopt(new)
    handover.update { it + (new - unfinished.toSet()) }
  }
}
