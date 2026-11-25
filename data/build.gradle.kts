plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android {
    namespace = "dev.sargunv.handheld.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(project(":domain"))
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.io.core)
      api(libs.ktor.client.core)
      implementation(libs.ktor.client.encoding)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
    jvmMain.dependencies { implementation(libs.ktor.client.java) }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
  }
}
