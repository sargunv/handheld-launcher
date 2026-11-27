import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import dev.sargunv.handheld.gradle.RequireLicenceTexts
import dev.sargunv.handheld.gradle.SessionLicences
import dev.sargunv.handheld.gradle.androidAbis

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.aboutlibraries.android)
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
    rcheevosLicence = root.file("session/vendor/rcheevos/LICENSE")
    outputDir = layout.buildDirectory.dir("generated/licences")
  }

// The About screen's list: the app's dependencies, which AboutLibraries puts in each variant's
// `R.raw.aboutlibraries`, with the session's entries. It runs offline: licence texts come from the
// entries, not the network.
aboutLibraries {
  offlineMode = true
  collect { configPath = sessionLicences.flatMap { it.outputDir } }
}

tasks.withType<AboutLibrariesTask>().configureEach { doLast(RequireLicenceTexts) }

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
  implementation(libs.kotlinx.serialization.json)
  implementation(project(":session"))
  testImplementation(libs.kotlin.test.junit)
}
