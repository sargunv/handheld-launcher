plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  android {
    namespace = "dev.sargunv.handheld.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  sourceSets {
    commonMain.dependencies { implementation(project(":domain")) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
