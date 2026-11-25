plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.sargunv.handheld"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.sargunv.handheld"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
    // The ABIs the game session builds for.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
  debugImplementation(project(":session"))
}
