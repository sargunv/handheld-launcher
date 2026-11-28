plugins {
  id("handheld.kmp-library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.sqldelight)
}

kotlin {
  android { withHostTest {} }

  sourceSets {
    commonMain.dependencies {
      api(project(":domain"))
      api(libs.kotlinx.coroutines.core)
      api(libs.ktor.client.core)
      implementation(libs.kermit)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.client.encoding)
      implementation(libs.ktor.client.okhttp)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.sqldelight.coroutines.extensions)
      implementation(libs.sqldelight.androidx.driver)
      implementation(libs.androidx.sqlite.bundled)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.junit)
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
