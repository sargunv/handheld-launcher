package dev.sargunv.handheld.ui.about

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The app's name, as About shows it. */
internal const val AppName = "Handheld"

/** Where the app's source code is, which its licence offers to everyone it reaches. */
internal const val SourceUrl = "https://github.com/sargunv/handheld-launcher"

/**
 * What About says about this build of the app: its [version], and the third-party components it's
 * built from.
 *
 * @param readComponents reads the list of components, in the JSON form that AboutLibraries' Gradle
 *   plugin writes, or null when the build has none.
 */
@Stable
class AppInfo(val version: String, private val readComponents: suspend () -> String?) {
  private val lock = Mutex()
  private var components: List<Component>? = null

  /** The components, by name, read once. Empty when the build has no list. */
  suspend fun components(): List<Component> = lock.withLock {
    components
      ?: withContext(Dispatchers.Default) { readComponents()?.let(::parseComponents).orEmpty() }
        .also { components = it }
  }

  companion object {
    /** A build that says nothing about itself, such as a test's. */
    val Unknown = AppInfo("unknown") { null }
  }
}

/** A third-party component the app is built from, such as a library or a crate. */
@Immutable
data class Component(
  /** Its identifier, unique in the list, such as its Maven coordinates. */
  val id: String,
  val name: String,
  val version: String?,
  val description: String?,
  val website: String?,
  /** The licences the app uses it under. */
  val licences: List<Licence>,
)

/** A licence's text, as a component carries it. */
@Immutable
data class Licence(
  /** Its SPDX identifier, or its name when it has none. */
  val id: String,
  val name: String,
  val text: String,
)

@Serializable
private class Definitions(
  val libraries: List<LibraryJson> = emptyList(),
  val licenses: Map<String, LicenseJson> = emptyMap(),
)

@Serializable
private class LibraryJson(
  val uniqueId: String,
  val name: String? = null,
  val artifactVersion: String? = null,
  val description: String? = null,
  val website: String? = null,
  val licenses: List<String> = emptyList(),
)

@Serializable
private class LicenseJson(val name: String, val spdxId: String? = null, val content: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/** Reads the components from AboutLibraries' JSON, sorted by name. */
internal fun parseComponents(text: String): List<Component> {
  val definitions = json.decodeFromString<Definitions>(text)
  return definitions.libraries
    .map { library ->
      Component(
        id = library.uniqueId,
        name = library.name?.takeIf { it.isNotBlank() } ?: library.uniqueId,
        version = library.artifactVersion?.takeIf { it.isNotBlank() },
        description = library.description?.takeIf { it.isNotBlank() },
        website = library.website?.takeIf { it.isNotBlank() },
        licences =
          library.licenses.mapNotNull { hash ->
            val licence = definitions.licenses[hash] ?: return@mapNotNull null
            Licence(
              id = licence.spdxId?.takeIf { it.isNotBlank() } ?: licence.name,
              name = licence.name,
              text = licence.content.orEmpty(),
            )
          },
      )
    }
    .sortedWith(compareBy<Component> { it.name.lowercase() }.thenBy { it.version })
}
