plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun sdk(name: String) = libs.findVersion(name).get().requiredVersion.toInt()

kotlin {
  android {
    namespace = "dev.sargunv.handheld.${project.name}"
    compileSdk = sdk("android-compileSdk")
    minSdk = sdk("android-minSdk")
  }
  jvm()
}

// The bundled SQLite loads native libraries.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
