plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

kotlin {
  android {
    namespace = "dev.sargunv.handheld.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(project(":domain"))
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.ui)
      implementation(libs.compose.unstyled)
    }
  }
}
