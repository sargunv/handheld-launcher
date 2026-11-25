plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  android {
    namespace = "dev.sargunv.handheld.domain"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()

  sourceSets {
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
