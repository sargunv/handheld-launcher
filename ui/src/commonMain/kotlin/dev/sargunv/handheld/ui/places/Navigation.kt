package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.ui.about.Component
import dev.sargunv.handheld.ui.game.OpenWithScope

/** The screens of the signed-in app. */
sealed interface Screen {
  /** The top-level places. */
  data object Places : Screen

  /**
   * A game, a ROM or a local game, which plays as it opens when [play] asks and the game is on the
   * device.
   */
  data class Game(val key: GameKey, val title: String, val play: Boolean = false) : Screen {
    constructor(
      romId: Long,
      title: String,
      play: Boolean = false,
    ) : this(GameKey.Romm(romId), title, play)
  }

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
  data class OnGame(val game: LibraryGame) : Focused

  data class OnCollection(val collection: Collection) : Focused

  data class OnComponent(val component: Component) : Focused
}
