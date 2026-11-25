plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.serialization)
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
      api(project(":data"))
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.ui)
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.serialization.core)
      implementation(libs.compose.unstyled)
      implementation(libs.navigation3.ui)
      implementation(libs.navigationevent.compose)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    jvmTest.dependencies {
      implementation(libs.compose.ui.test)
      implementation(compose.desktop.currentOs)
    }
  }
}
