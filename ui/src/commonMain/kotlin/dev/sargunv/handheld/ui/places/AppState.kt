package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.downloads.UploadManager
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.data.settings.LibraryTab
import dev.sargunv.handheld.data.settings.Settings
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.library.OpenedFrom
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.settings.SettingsView
import dev.sargunv.handheld.ui.signin.SignInState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * State the app's two screens share, over [data] and [platform], with [data]'s [settings] as the
 * composition holds them.
 */
@Stable
class AppState(private val data: HandheldData, platform: AppPlatform, settings: State<Settings>) {
  /** The sign-in to the RomM server, its client and the library's sync. */
  val romm: RommSession = data.romm
  val library: LibraryCache = data.library
  val downloads: DownloadManager = data.downloads
  val uploads: UploadManager = data.uploads
  val games: DeviceGames = data.games
  val launcher: GameLauncher = platform.launcher
  val apps: AppCatalog = platform.apps

  /** The RetroAchievements account, on platforms whose games can earn achievements. */
  val retroAchievements: RetroAchievementsState? =
    platform.retroAchievements?.let(::RetroAchievementsState)

  /** What About says about this build. */
  val appInfo: AppInfo = platform.appInfo

  /** Reads what a game's file says about itself. */
  val romHeaders: RomHeaders = platform.romHeaders

  /** The settings, which [updateSettings] changes. */
  val settings: Settings by settings

  fun updateSettings(change: (Settings) -> Settings) = data.settings.update(change)

  /** The games whose play isn't over. */
  val unfinished: StateFlow<Set<GameKey>> = data.adopter.unfinished

  /** The adoptions still listed that the adopt step has handed over. */
  val handedOver: StateFlow<Set<Adoption>> = data.adopter.handedOver

  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)

  val libraryView = LibraryState(library, downloads.statuses(), games.localGames()) { libraryTab }
  val settingsView = SettingsView()

  private val device = platform.device

  /** The sign-in that shows while the device isn't signed in. */
  var signIn: SignInState by mutableStateOf(SignInState(romm.signIn, device))
    private set

  private val homeStartRequests = Channel<Unit>(Channel.CONFLATED)

  /** The requests to start over on Home, which [MainPrimary] carries out. */
  internal val homeStarts: Flow<Unit> = homeStartRequests.receiveAsFlow()

  /** The Library tab that shows, which stays between launches. */
  var libraryTab: LibraryTab
    get() = settings.library.tab
    set(value) {
      if (value != libraryTab) updateSettings { it.copy(library = it.library.copy(tab = value)) }
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
   */
  fun signOut(allowUploads: Boolean = false, keepQueue: Boolean = false) {
    val address = romm.account?.credentials?.baseUrl.orEmpty()
    data.signOut(keepQueue)
    signIn = SignInState(romm.signIn, device, address, allowUploads)
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
   * Runs [launch], which calls [launcher]'s play of [game] before it first suspends, once what was
   * kept for [game]'s file has been handed to it.
   */
  suspend fun play(game: GameKey, launch: suspend () -> List<String>): List<String> =
    data.adopter.play(game, launch)
}

/** Whether this is signed in, or null when it isn't known yet. */
internal val RommState.signedIn: Boolean?
  get() =
    when (this) {
      RommState.Unknown -> null
      RommState.SignedOut -> false
      is RommAccount -> true
    }
