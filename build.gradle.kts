import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import groovy.json.JsonSlurper

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.aboutlibraries) apply false
  alias(libs.plugins.aboutlibraries.android) apply false
}

/**
 * Fails an AboutLibraries task whose output has a component without a licence text. The About
 * screen shows each component's licence texts, and AboutLibraries runs offline here, so the texts
 * have to come from the entries in `licences/` or the session's.
 */
object RequireLicenceTexts : Action<Task> {
  override fun execute(task: Task) {
    val outputs =
      task.outputs.files.flatMap { if (it.isDirectory) it.walk().toList() else listOf(it) }
    for (file in outputs.filter { it.isFile && it.extension == "json" }) {
      @Suppress("UNCHECKED_CAST") val data = JsonSlurper().parse(file) as Map<String, Any?>
      @Suppress("UNCHECKED_CAST") val licences = data["licenses"] as Map<String, Map<String, Any?>>
      @Suppress("UNCHECKED_CAST") val libraries = data["libraries"] as List<Map<String, Any?>>
      fun hasText(hash: Any?) = !(licences[hash]?.get("content") as String?).isNullOrBlank()
      val missing = libraries.mapNotNull { library ->
        val hashes = library["licenses"] as List<*>? ?: emptyList<Any?>()
        val without = hashes.filterNot(::hasText)
        when {
          hashes.isEmpty() -> "  ${library["uniqueId"]}: no licence"
          without.isNotEmpty() -> "  ${library["uniqueId"]}: ${without.joinToString()}"
          else -> null
        }
      }
      if (missing.isEmpty()) continue
      throw GradleException(
        "These components have no licence text for the About screen:\n" +
          missing.joinToString("\n") +
          "\nAdd each licence's text to licences/licenses/, as the others there are, or an entry " +
          "for the component to licences/libraries/.",
      )
    }
  }
}

subprojects {
  tasks.withType<AboutLibrariesTask>().configureEach { doLast(RequireLicenceTexts) }
}
