package dev.sargunv.handheld.ui.places

import dev.sargunv.handheld.domain.GameKey
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
