plugins {
  kotlin("jvm")
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
  id("com.mikepenz.aboutlibraries.plugin")
  id("handheld.licences")
}

// The About screen's list, as a resource: the app's dependencies, with the entries every app
// shares.
val licences = layout.buildDirectory.dir("generated/licences")

aboutLibraries {
  collect { configPath = rootProject.layout.projectDirectory.dir("licences") }
  export { outputFile = licences.map { it.file("aboutlibraries.json") } }
}

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
