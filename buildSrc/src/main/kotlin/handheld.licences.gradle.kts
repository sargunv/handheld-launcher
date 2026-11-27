import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import dev.sargunv.handheld.gradle.RequireLicenceTexts

for (plugin in listOf("plugin", "plugin.android")) {
  pluginManager.withPlugin("com.mikepenz.aboutlibraries.$plugin") {
    configure<AboutLibrariesExtension> { offlineMode = true }
  }
}

tasks.withType<AboutLibrariesTask>().configureEach { doLast(RequireLicenceTexts) }
