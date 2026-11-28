package dev.sargunv.handheld.data.settings

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchRules
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The settings the app keeps between launches. Each is changed where it applies: a game's in its
 * menu, and the device-wide ones on the Settings page.
 *
 * Add a setting as a property with a default. The default is also its value on a device that stored
 * its settings before the property existed. A setting kept per game goes in a map keyed by
 * [GameKey], which [adopted] hands from a local game to its ROM, so add the map there too.
 */
@Serializable
data class Settings(
  val input: InputSettings = InputSettings(),
  val home: HomeSettings = HomeSettings(),
  val library: LibrarySettings = LibrarySettings(),
  /** The apps the user picked to open games in, on the Open with screen. */
  val launch: LaunchRules = LaunchRules(),
  /**
   * Whether the launcher checks if a standalone emulator is still running, and offers to close it,
   * on devices that let it.
   */
  val closeEmulators: Boolean = false,
  val achievements: AchievementSettings = AchievementSettings(),
  /** Which storage new downloads go to, and in what order. */
  val storage: StorageSettings = StorageSettings(),
) {
  /** These settings with what each of the [adoptions]' local games keeps handed to its ROM. */
  fun adopted(adoptions: List<Adoption>): Settings = copy(launch = launch.adopted(adoptions))
}

/**
 * The settings, kept in [file]. A change shows at once, and the latest one goes to the file after.
 * When storage fails, as on a full disk, the old file stays and the app carries on with the new
 * settings until it closes.
 */
class SettingsStore(file: File, scope: CoroutineScope) {
  private val stored = JsonFile(file, Settings.serializer())
  private val current = MutableStateFlow(stored.read() ?: Settings())

  val settings: StateFlow<Settings> = current.asStateFlow()

  fun update(change: (Settings) -> Settings) = current.update(change)

  init {
    // Undispatched, so it's following the settings before any change.
    scope.launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
      current.drop(1).collect {
        try {
          stored.write(it)
        } catch (e: IOException) {
          Logger.withTag("Settings").w(e) { "Couldn't write $file" }
        }
      }
    }
  }
}

/** Which face button confirms. The other one of the pair goes back. */
enum class ConfirmButton {
  A,
  B,
}

/**
 * Where the A button sits, and with it the other face buttons. Face buttons map by key code, not
 * position: a handheld's own layout setting decides which physical button sends `BUTTON_A`, and
 * navigation follows the key code. The layout matters only where position does: the built-in
 * session's RetroPad, which is positional, and showing the buttons where they sit.
 */
enum class AButtonPosition {
  /** A at the bottom: A south, B east, X west, Y north. Android's documented default. */
  South,

  /** A on the right: A east, B south, X north, Y west. */
  East,
}

/** The player's input preferences. */
@Serializable
data class InputSettings(
  val confirmButton: ConfirmButton = ConfirmButton.A,
  val aButtonPosition: AButtonPosition = AButtonPosition.South,
)

/** A shelf on the home screen, below the game the user was last playing. */
enum class HomeShelf(val title: String) {
  RecentlyPlayed("Recently played"),
  Collections("Collections"),
  RecentlyAdded("Recently added to your server"),
}

/** Which home shelves show, and in what order. Every shelf shows by default. */
@Serializable
data class HomeSettings(
  /** The shelves in the order they show, hidden ones included. */
  val order: List<HomeShelf> = HomeShelf.entries,
  val hidden: Set<HomeShelf> = emptySet(),
) {
  /** The shelves that show, in order. */
  val visible: List<HomeShelf>
    get() = order.filter { it !in hidden }

  fun hide(shelf: HomeShelf) = copy(hidden = hidden + shelf)

  fun show(shelf: HomeShelf) = copy(hidden = hidden - shelf)

  /**
   * Moves [shelf] before the shelf above it among [shown], the shelves on screen. Does nothing to
   * the first one.
   */
  fun moveUp(shelf: HomeShelf, shown: List<HomeShelf>) = swap(shelf, shown, -1)

  /**
   * Moves [shelf] after the shelf below it among [shown], the shelves on screen. Does nothing to
   * the last one.
   */
  fun moveDown(shelf: HomeShelf, shown: List<HomeShelf>) = swap(shelf, shown, 1)

  private fun swap(shelf: HomeShelf, shown: List<HomeShelf>, step: Int): HomeSettings {
    if (shelf !in shown) return this
    val neighbor = shown.getOrNull(shown.indexOf(shelf) + step) ?: return this
    val reordered = order.map {
      when (it) {
        shelf -> neighbor
        neighbor -> shelf
        else -> it
      }
    }
    return copy(order = reordered)
  }
}

/** Library's tabs, in the order L and R step through them. */
enum class LibraryTab(val title: String) {
  OnDevice("On device"),
  Platforms("Platforms"),
  Collections("Collections"),
  AllGames("All games"),
}

/** Library's settings. */
@Serializable
data class LibrarySettings(
  /** The tab Library shows when it opens: the one it was last left on. */
  val tab: LibraryTab = LibraryTab.Platforms,
)

/** How games in the built-in session take part in RetroAchievements. */
@Serializable
data class AchievementSettings(
  /**
   * Whether unlocks count as hardcore. In hardcore, games with achievements can't load states or
   * fast forward, as RetroAchievements requires.
   */
  val hardcore: Boolean = false,
)
