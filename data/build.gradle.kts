import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.sqldelight)
}

kotlin {
  android {
    namespace = "dev.sargunv.handheld.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  applyDefaultHierarchyTemplate {
    common {
      // Android and the desktop both run on a JVM, so they share its zip support.
      group("jvmShared") {
        withJvm()
        withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":domain"))
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.io.core)
      api(libs.ktor.client.core)
      implementation(libs.ktor.client.encoding)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.sqldelight.coroutines.extensions)
      implementation(libs.sqldelight.androidx.driver)
      implementation(libs.androidx.sqlite.bundled)
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

sqldelight {
  databases {
    register("LibraryDatabase") {
      packageName = "dev.sargunv.handheld.data.cache.db"
      dialect(libs.sqldelight.sqlite.dialect)
      // The androidx driver only has a suspending API.
      generateAsync = true
    }
  }
}

// The bundled SQLite loads a native library.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
