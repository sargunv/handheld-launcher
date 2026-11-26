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
 * Fails an AboutLibraries task whose output has a licence without its text. The About screen shows
 * each licence's text, and AboutLibraries runs offline here, so a licence's text has to come from
 * the entries in `licences/` or the session's.
 */
object RequireLicenceTexts : Action<Task> {
  override fun execute(task: Task) {
    val outputs =
      task.outputs.files.flatMap { if (it.isDirectory) it.walk().toList() else listOf(it) }
    for (file in outputs.filter { it.isFile && it.extension == "json" }) {
      @Suppress("UNCHECKED_CAST") val data = JsonSlurper().parse(file) as Map<String, Any?>
      @Suppress("UNCHECKED_CAST") val licences = data["licenses"] as Map<String, Map<String, Any?>>
      @Suppress("UNCHECKED_CAST") val libraries = data["libraries"] as List<Map<String, Any?>>
      val missing = licences.filterValues { (it["content"] as String?).isNullOrBlank() }.keys
      if (missing.isEmpty()) continue
      val users =
        missing.joinToString("\n") { hash ->
          val ids = libraries.filter { hash in (it["licenses"] as List<*>) }.map { it["uniqueId"] }
          "  $hash, for ${ids.joinToString()}"
        }
      throw GradleException(
        "These licences have no text for the About screen:\n$users\n" +
          "Add each one's text to licences/licenses/, as the others there are.",
      )
    }
  }
}

subprojects {
  tasks.withType<AboutLibrariesTask>().configureEach { doLast(RequireLicenceTexts) }
}
