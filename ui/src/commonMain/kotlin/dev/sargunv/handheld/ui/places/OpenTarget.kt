package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.ui.settings.SettingsSection

/**
 * A screen to show straight away, for development: debug builds take one from the launch intent, so
 * a tool can reach a screen without driving the controls there.
 */
@Immutable
sealed interface OpenTarget {
  /** A top-level place. */
  data class AtPlace(val place: Place) : OpenTarget

  /** The Game screen for the game with RomM id [romId]. */
  data class Game(val romId: Long) : OpenTarget

  /** Library: the games of the platform with [platformSlug], or the list of shelves when null. */
  data class LibraryShelf(val platformSlug: String?) : OpenTarget

  /** Settings, on [section], or on the section it was last left on when null. */
  data class Settings(val section: SettingsSection?) : OpenTarget

  companion object {
    /**
     * The target [text] names: `place:<place>` (such as `place:downloads`), `game:<romId>`,
     * `library`, `library:<platform-slug>`, `settings` or `settings:<section>` (such as
     * `settings:romm`). Null when it names none of those.
     */
    fun parse(text: String): OpenTarget? {
      val kind = text.substringBefore(':')
      val arg = text.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
      return when (kind) {
        "place" -> Place.entries.find { it.name.equals(arg, ignoreCase = true) }?.let(::AtPlace)
        "game" -> arg?.toLongOrNull()?.let(::Game)
        "library" -> LibraryShelf(arg)
        "settings" ->
          if (arg == null) Settings(null)
          else
            SettingsSection.entries.find { it.name.equals(arg, ignoreCase = true) }?.let(::Settings)
        else -> null
      }
    }
  }
}

/**
 * A request to show [target]. Each request is its own, so asking for the same target twice shows it
 * twice, even after the player moved away in between.
 */
class OpenRequest(val target: OpenTarget)

/**
 * A press of the system's HOME while the app is already in front, as the home app. It closes the
 * open dialogs when there are any, and otherwise starts over on Home, the way launchers do. Each
 * press is its own, like an [OpenRequest].
 */
class HomePress
