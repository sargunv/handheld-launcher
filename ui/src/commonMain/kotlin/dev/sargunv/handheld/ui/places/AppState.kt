package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.data.settings.LibraryTab
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.achievements.RetroAchievementsState
import dev.sargunv.handheld.ui.library.LibraryState
import dev.sargunv.handheld.ui.library.OpenedFrom
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.settings.SettingsView
import dev.sargunv.handheld.ui.signin.SignInState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What the app's two screens show, over [data] and [platform]. */
@Stable
class AppState(val data: HandheldData, val platform: AppPlatform) {
  /** The RetroAchievements sign-in's fields, on platforms whose games can earn achievements. */
  val retroAchievements: RetroAchievementsState? =
    platform.retroAchievements?.let(::RetroAchievementsState)

  val navigator = Navigator<Screen>(Screen.Places)
  var place: Place by mutableStateOf(Place.Home)
  var focused: Focused? by mutableStateOf(null)

  val libraryView = LibraryState(data)
  val settingsView = SettingsView()

  /** The sign-in that shows while the device isn't signed in. */
  var signIn: SignInState by mutableStateOf(SignInState(data.romm.signIn, platform.device))
    private set

  private val homeStartRequests = Channel<Unit>(Channel.CONFLATED)

  /** The requests to start over on Home, which [MainPrimary] carries out. */
  internal val homeStarts: Flow<Unit> = homeStartRequests.receiveAsFlow()

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
    libraryView.tab = tab
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
    val address = data.romm.account?.credentials?.baseUrl.orEmpty()
    data.signOut(keepQueue)
    signIn = SignInState(data.romm.signIn, platform.device, address, allowUploads)
    navigator.backToStart()
    place = Place.Home
  }
}

/** Whether this is signed in, or null when it isn't known yet. */
internal val RommState.signedIn: Boolean?
  get() =
    when (this) {
      RommState.Unknown -> null
      RommState.SignedOut -> false
      is RommAccount -> true
    }
