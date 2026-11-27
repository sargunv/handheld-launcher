import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import dev.sargunv.handheld.gradle.RequireLicenceTexts

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.aboutlibraries)
  id("handheld.build-logic")
}

// The About screen's list, as a resource: the app's dependencies, with the entries every app
// shares. It runs offline: licence texts come from the entries, not the network.
val licences = layout.buildDirectory.dir("generated/licences")

aboutLibraries {
  offlineMode = true
  collect { configPath = rootProject.layout.projectDirectory.dir("licences") }
  export { outputFile = licences.map { it.file("aboutlibraries.json") } }
}

tasks.withType<AboutLibrariesTask>().configureEach { doLast(RequireLicenceTexts) }

sourceSets.main { resources.srcDir(files(licences).builtBy("exportLibraryDefinitions")) }

dependencies {
  implementation(project(":ui"))
  implementation(project(":data"))
  implementation(compose.desktop.currentOs)
}

compose.desktop {
  application {
    mainClass = "dev.sargunv.handheld.desktop.MainKt"
    jvmArgs("-Dhandheld.version=${libs.versions.handheld.get()}")
  }
}
