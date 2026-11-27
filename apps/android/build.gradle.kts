import dev.sargunv.handheld.gradle.SessionLicences
import dev.sargunv.handheld.gradle.androidAbis

plugins {
  id("com.android.application")
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  id("com.mikepenz.aboutlibraries.plugin.android")
  id("handheld.licences")
}

val sessionLicences =
  tasks.register<SessionLicences>("sessionLicences") {
    description = "Writes the About screen's entries for the game session's crates and C code."
    val root = rootProject.layout.projectDirectory
    manifests.from(
      root.file("Cargo.toml"),
      root.file("Cargo.lock"),
      root.file("session/Cargo.toml"),
      root.file("rom-headers/Cargo.toml"),
      root.file("session/about.toml"),
      // It pins cargo-about.
      root.file("mise.toml"),
    )
    workspace = root
    targets = androidAbis.values.toList()
    checkedIn.from(root.dir("licences"), root.dir("session/licences"))
    outputDir = layout.buildDirectory.dir("generated/licences")
  }

// The About screen's list: the app's dependencies, which AboutLibraries puts in each variant's
// `R.raw.aboutlibraries`, with the session's entries.
aboutLibraries { collect { configPath = sessionLicences.flatMap { it.outputDir } } }

android {
  namespace = "dev.sargunv.handheld"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.sargunv.handheld"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = libs.versions.handheld.get()
    ndk { abiFilters += androidAbis.keys }
  }

  // The project's shared debug key, which `mise run android:debug-key` unpacks from fnox, so every
  // machine's debug build installs over the others'. Without it, the machine's own key signs.
  val sharedDebugKey = file("debug.keystore")
  if (sharedDebugKey.exists()) signingConfigs.getByName("debug") { storeFile = sharedDebugKey }
}

dependencies {
  implementation(project(":ui"))
  implementation(project(":data"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core)
  implementation(libs.kermit)
  implementation(libs.kotlinx.serialization.json)
  implementation(project(":session"))
  testImplementation(libs.kotlin.test.junit)
}
