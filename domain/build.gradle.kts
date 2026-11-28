plugins {
  id("handheld.kmp-library")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  sourceSets {
    commonMain.dependencies { api(libs.kotlinx.serialization.core) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
