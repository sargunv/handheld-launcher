package dev.sargunv.handheld.ui.about

import androidx.compose.runtime.Stable
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's name, as About shows it. */
internal const val AppName = "Handheld"

/** Where the app's source code is, which its licence offers to everyone it reaches. */
internal const val SourceUrl = "https://github.com/sargunv/handheld-launcher"

/**
 * What About says about this build of the app: its [version], and the third-party components it's
 * built from.
 *
 * @param readLibraries reads the list of components, in the JSON form that AboutLibraries' Gradle
 *   plugin writes, or null when the build has none.
 */
@Stable
class AppInfo(val version: String, readLibraries: () -> String?) {
  private val libraries by lazy {
    readLibraries()?.let { Libs.Builder().withJson(it).build().libraries }.orEmpty()
  }

  /** The components, by name, read once. Empty when the build has no list. */
  suspend fun libraries(): List<Library> = withContext(Dispatchers.Default) { libraries }

  companion object {
    /** A build that says nothing about itself, such as a test's. */
    val Unknown = AppInfo("unknown") { null }
  }
}
