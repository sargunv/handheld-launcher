package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.ui.library.LibraryShelf
import dev.sargunv.handheld.ui.library.LibraryTab
import dev.sargunv.handheld.ui.settings.SettingsSection
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A screen to show straight away, for development: the app takes one from the launch intent's
 * `open` extra, so a tool can reach a screen without driving the controls there.
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
    /** The target [text] names, as `mise run android:open --help` lists them, or null. */
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
 * Shows [target], on top of the places, as if the player had gone there. A game or platform that
 * the cache doesn't hold yet shows once the sync under way brings it, if it does.
 */
internal suspend fun AppState.open(target: OpenTarget) {
  navigator.backToStart()
  when (target) {
    is OpenTarget.AtPlace -> showPlace(target.place)
    is OpenTarget.Game -> {
      val rom = findOrAfterSync { library.rom(target.romId) }
      if (rom != null) navigator.push(Screen.Game(rom.id, rom.title))
    }
    is OpenTarget.Local -> {
      // A scan before the sync brings the platforms finds no local games, so each look waits a
      // while for the scan that follows.
      val local = findOrAfterSync {
        withTimeoutOrNull(LOCAL_SCAN_WAIT) {
          games.localGame(target.path).first {
            it is LocalGameState.Local || it is LocalGameState.Matched
          }
        }
      }
      // The file may have matched a game of the library since.
      when (local) {
        is LocalGameState.Local -> navigator.push(Screen.Game(local.game.key, local.game.title))
        is LocalGameState.Matched ->
          library.rom(local.romId)?.let { navigator.push(Screen.Game(it.id, it.title)) }
        LocalGameState.Gone,
        null -> Unit
      }
    }
    is OpenTarget.Library -> {
      showPlace(Place.Library)
      libraryView.close()
      target.tab?.let { libraryTab = it }
    }
    is OpenTarget.LibraryPlatform -> {
      showPlace(Place.Library)
      libraryView.close()
      val platform =
        findOrAfterSync { library.platforms().first().find { it.slug == target.slug } } ?: return
      openShelf(LibraryTab.Platforms, LibraryShelf.OfPlatform(platform).key)
    }
    is OpenTarget.LibraryCollection -> {
      showPlace(Place.Library)
      libraryView.close()
      val collection =
        findOrAfterSync {
          library.collections().first().find { it.kind == target.kind && it.id == target.id }
        } ?: return
      openShelf(LibraryTab.Collections, LibraryShelf.OfCollection(collection).key)
    }
    is OpenTarget.Settings -> {
      showPlace(Place.Settings)
      target.section?.let(settingsView::jumpTo)
    }
  }
}

/** What [find] finds now, or else once the sync under way is done. */
private suspend fun <T : Any> AppState.findOrAfterSync(find: suspend () -> T?): T? =
  find()
    ?: run {
      romm.sync.first { it.isDone }
      find()
    }

/** How long opening a local game waits for a scan to find it. */
private val LOCAL_SCAN_WAIT = 10.seconds
