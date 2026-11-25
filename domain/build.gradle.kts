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
    getByName("androidHostTest").dependencies {
      implementation(libs.junit)
      implementation(libs.robolectric)
    }
  }
}

// Robolectric downloads Android's framework jar when a test first runs. Resolving it here instead
// keeps test runs offline and lets Gradle cache it.
val robolectricAndroidAll: Configuration by configurations.creating { isTransitive = false }

dependencies { robolectricAndroidAll(libs.robolectric.android.all) }

val robolectricJars =
  tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-jars"))
  }

tasks.withType<Test>().configureEach {
  if (name.contains("AndroidHostTest")) {
    dependsOn(robolectricJars)
    // Robolectric loads its native runtime with System.load.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("robolectric.offline", "true")
    systemProperty(
      "robolectric.dependency.dir",
      layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath,
    )
  }
}
