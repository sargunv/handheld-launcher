package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.ui.library.LibraryTab
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

  /**
   * The Game screen for the local game whose file is at [path], or for the game of the library the
   * file is now.
   */
  data class Local(val path: String) : OpenTarget

  /** Library, on [tab], or on the tab it was last left on when null. */
  data class Library(val tab: LibraryTab?) : OpenTarget

  /** Library: the games of the platform with [slug]. */
  data class LibraryPlatform(val slug: String) : OpenTarget

  /** Library: the games of the collection of [kind] with RomM id [id]. */
  data class LibraryCollection(val kind: CollectionKind, val id: Long) : OpenTarget

  /** Settings, on [section], or on the section it was last left on when null. */
  data class Settings(val section: SettingsSection?) : OpenTarget

  companion object {
    /**
     * The target [text] names: `place:<place>` (such as `place:downloads`), `game:<romId>`,
     * `local:<path>` (a local game's file), `library`, `library:tab:<tab>` (such as
     * `library:tab:on-device`), `library:<platform-slug>`, `library:collection:<id>`,
     * `library:collection:smart:<id>`, `settings` or `settings:<section>` (such as
     * `settings:romm`). Null when it names none of those.
     */
    fun parse(text: String): OpenTarget? {
      val kind = text.substringBefore(':')
      val arg = text.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
      return when (kind) {
        "place" -> Place.entries.find { it.name.equals(arg, ignoreCase = true) }?.let(::AtPlace)
        "game" -> arg?.toLongOrNull()?.let(::Game)
        "local" -> arg?.takeIf { it.startsWith('/') }?.let(::Local)
        "library" -> parseLibrary(arg)
        "settings" ->
          if (arg == null) Settings(null)
          else
            SettingsSection.entries.find { it.name.equals(arg, ignoreCase = true) }?.let(::Settings)
        else -> null
      }
    }

    private fun parseLibrary(arg: String?): OpenTarget? {
      if (arg == null) return Library(null)
      val kind = arg.substringBefore(':')
      val rest = arg.substringAfter(':', missingDelimiterValue = "")
      return when (kind) {
        "tab" ->
          LibraryTab.entries
            .find { it.name.equals(rest.replace("-", ""), ignoreCase = true) }
            ?.let(::Library)
        "collection" ->
          if (rest.startsWith("smart:")) {
            rest.removePrefix("smart:").toLongOrNull()?.let {
              LibraryCollection(CollectionKind.Smart, it)
            }
          } else {
            rest.toLongOrNull()?.let { LibraryCollection(CollectionKind.Regular, it) }
          }
        else -> LibraryPlatform(arg)
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
