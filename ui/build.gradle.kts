plugins {
  id("handheld.kmp-library")
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
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
      implementation(libs.coil.compose)
      implementation(libs.coil.network.ktor3)
      implementation(libs.aboutlibraries.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    jvmTest.dependencies {
      implementation(libs.compose.ui.test)
      implementation(libs.ktor.client.mock)
      implementation(compose.desktop.currentOs)
    }
  }
}
