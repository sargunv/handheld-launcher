import dev.sargunv.handheld.gradle.CargoBuild
import dev.sargunv.handheld.gradle.UniffiBindgen
import dev.sargunv.handheld.gradle.androidAbis

plugins { id("com.android.library") }

val workspaceDir: Directory = rootProject.layout.projectDirectory
// The NDK mise.toml pins, in a directory named for its version, builds and strips the library.
val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
val rustSources =
  files(
    fileTree("src"),
    fileTree("vendor"),
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
    outputDir = layout.buildDirectory.dir("generated/session/hostLibs")
  }

val generateBindings =
  tasks.register<UniffiBindgen>("generateBindings") {
    description = "Generates the session's Kotlin bindings."
    library.from(cargoBuildHost.map { it.outputDir.asFileTree })
    config.from("uniffi.toml")
    sources.from(
      fileTree("bindgen") { include("Cargo.toml", "src/**") },
      workspaceDir.file("Cargo.toml"),
      workspaceDir.file("Cargo.lock"),
    )
    workspace = workspaceDir
    outputDir = layout.buildDirectory.dir("generated/session/bindings")
  }

android {
  namespace = "dev.sargunv.handheld.session"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  ndkHome.orNull?.let {
    ndkPath = it
    ndkVersion = File(it).name
  }
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  // src holds the Rust crate.
  sourceSets["main"].kotlin.directories += "kotlin"
}

dependencies {
  implementation("${libs.jna.get()}@aar")
  implementation(libs.kermit)
  implementation(libs.kotlinx.coroutines.core)
}

val cargoBuildAndroid =
  tasks.register<CargoBuild>("cargoBuildAndroid") {
    description = "Builds the session library for each Android ABI."
    sources.from(rustSources)
    workspace = workspaceDir
    // `-Phandheld.session.abis=x86_64,...` narrows them.
    abis =
      providers
        .gradleProperty("handheld.session.abis")
        .map { it.split(",").map(String::trim) }
        .orElse(androidAbis.keys.toList())
    minSdk = libs.versions.android.minSdk
    // cargo-ndk reads the NDK from the environment; a new one rebuilds.
    inputs.property("ndk", ndkHome.orElse(""))
    outputDir = layout.buildDirectory.dir("generated/session/jniLibs")
  }

androidComponents.onVariants { variant ->
  variant.sources.kotlin?.addGeneratedSourceDirectory(generateBindings, UniffiBindgen::outputDir)
  variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoBuild::outputDir)
}
