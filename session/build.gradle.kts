import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.sun.jna.Platform
import dev.sargunv.handheld.gradle.CargoBuild
import dev.sargunv.handheld.gradle.UniffiBindgen

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  id("handheld.build-logic")
}

// The Rust crate builds with cargo from mise's toolchain: for the JVM, the host's library, which
// also gives the Kotlin bindings; for Android, a release build for each ABI with the pinned NDK.

val workspaceDir: Directory = rootProject.layout.projectDirectory
val hostLibraryName: String = System.mapLibraryName("handheld_session")
val rustSources =
  files(
    fileTree("src") { include("**/*.rs", "**/*.h") },
    fileTree("vendor"),
    fileTree("bindgen") { include("Cargo.toml", "src/**") },
    // The workspace's crates the session links.
    fileTree(workspaceDir.dir("rom-headers")) { include("Cargo.toml", "src/**") },
    "build.rs",
    "Cargo.toml",
    "uniffi.toml",
    workspaceDir.file("Cargo.toml"),
    workspaceDir.file("Cargo.lock"),
  )

val cargoBuildHost =
  tasks.register<CargoBuild>("cargoBuildHost") {
    description = "Builds the session library for this host."
    sources.from(rustSources)
    workspace = workspaceDir
    // JNA looks for the library in its platform's directory on the classpath.
    targets = mapOf(Platform.RESOURCE_PREFIX to "")
    release = false
    environment = emptyMap()
    libraryFileName = hostLibraryName
    outputDir = layout.buildDirectory.dir("generated/session/hostLibs")
  }

val generateBindings =
  tasks.register<UniffiBindgen>("generateBindings") {
    description = "Generates the session's Kotlin bindings."
    library.from(
      cargoBuildHost.map { it.outputDir.file("${Platform.RESOURCE_PREFIX}/$hostLibraryName") },
    )
    config.from("uniffi.toml")
    workspace = workspaceDir
    outputDir = layout.buildDirectory.dir("generated/session/bindings")
  }

kotlin {
  android {
    namespace = "dev.sargunv.handheld.session"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  sourceSets {
    androidMain {
      kotlin.srcDir(generateBindings.map { it.outputDir })
      dependencies { implementation("${libs.jna.get()}@aar") }
    }
    jvmMain {
      kotlin.srcDir(generateBindings.map { it.outputDir })
      resources.srcDir(cargoBuildHost.map { it.outputDir })
      dependencies { implementation(libs.jna) }
    }
    jvmTest.dependencies { implementation(libs.kotlin.test) }
  }
}

val androidComponents = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()

/** Android ABIs by Rust target, narrowed with `-Phandheld.session.abis=x86_64,...`. */
val androidAbis: Map<String, String> = run {
  val all = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")
  val selected =
    providers.gradleProperty("handheld.session.abis").orNull?.split(",")?.map(String::trim)
  if (selected == null) all else all.filterKeys { it in selected }
}

val cargoBuildAndroid =
  tasks.register<CargoBuild>("cargoBuildAndroid") {
    description = "Builds the session library for each Android ABI."
    val ndkVersion = libs.versions.android.ndk.get()
    val minSdk = libs.versions.android.minSdk.get()
    val ndk = androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/$ndkVersion") }
    val hostTag = if (Platform.isMac()) "darwin-x86_64" else "linux-x86_64"
    sources.from(rustSources)
    workspace = workspaceDir
    targets = androidAbis
    release = true
    libraryFileName = "libhandheld_session.so"
    environment = ndk.map { dir ->
      check(dir.asFile.isDirectory) {
        "Android NDK $ndkVersion isn't installed; install it with " +
          "`sdkmanager --install \"ndk;$ndkVersion\"`"
      }
      val bin = dir.dir("toolchains/llvm/prebuilt/$hostTag/bin").asFile
      androidAbis.values
        .flatMap { triple ->
          val clang = bin.resolve("$triple$minSdk-clang").absolutePath
          val env = triple.replace('-', '_')
          listOf(
            "CARGO_TARGET_${env.uppercase()}_LINKER" to clang,
            "CC_$env" to clang,
            "AR_$env" to bin.resolve("llvm-ar").absolutePath,
          )
        }
        .toMap()
    }
    outputDir = layout.buildDirectory.dir("generated/session/jniLibs")
  }

androidComponents.onVariants { variant ->
  variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoBuild::outputDir)
}

tasks.named<Test>("jvmTest") {
  // JNA loads the session library with System.load.
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // The tests run the cores that `mise run session:fixtures` builds, wherever the tests look.
  val cache =
    providers
      .environmentVariable("XDG_CACHE_HOME")
      .orElse(providers.systemProperty("user.home").map { "$it/.cache" })
  val fixtures =
    providers
      .environmentVariable("HANDHELD_SESSION_FIXTURES")
      .orElse(cache.map { "$it/handheld/session-fixtures" })
  inputs.files(fixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
}
