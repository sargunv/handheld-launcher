import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import javax.inject.Inject

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
}

// The Rust crate builds with cargo from mise's toolchain: for the JVM, the host's library, which
// also gives the Kotlin bindings; for Android, a release build for each ABI with the pinned NDK.

/** Runs `cargo build` for the session library and copies it to [outputDir]/[subdir]. */
abstract class CargoBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection
  @get:Internal abstract val workspace: DirectoryProperty
  /** Rust target triples by output subdirectory; an empty triple builds for the host. */
  @get:Input abstract val targets: MapProperty<String, String>
  @get:Input abstract val release: Property<Boolean>
  @get:Input abstract val environment: MapProperty<String, String>
  @get:Input abstract val libraryFileName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun build() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    val profile = if (release.get()) "release" else "debug"
    for ((subdir, triple) in targets.get()) {
      exec.exec {
        workingDir = workspace.get().asFile
        environment(this@CargoBuild.environment.get())
        commandLine(
          buildList {
            addAll(listOf("cargo", "build", "--package", "handheld-session", "--lib"))
            if (release.get()) add("--release")
            if (triple.isNotEmpty()) addAll(listOf("--target", triple))
          },
        )
      }
      val targetDir =
        workspace.get().dir("target").let { if (triple.isEmpty()) it else it.dir(triple) }
      val library = targetDir.dir(profile).file(libraryFileName.get()).asFile
      library.copyTo(out.resolve(subdir).resolve(libraryFileName.get()), overwrite = true)
    }
  }
}

/** Generates the session's Kotlin bindings from a build of its library. */
abstract class UniffiBindgen @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val library: ConfigurableFileCollection
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val config: ConfigurableFileCollection
  @get:Internal abstract val workspace: DirectoryProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    exec.exec {
      workingDir = workspace.get().asFile
      commandLine(
        "cargo",
        "run",
        "--quiet",
        "--package",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        library.singleFile.absolutePath,
        "--language",
        "kotlin",
        "--no-format",
        "--out-dir",
        out.absolutePath,
      )
    }
  }
}

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

/** JNA's name for this host, the directory it looks for native libraries in on the classpath. */
val jnaPlatform: String = run {
  val os = System.getProperty("os.name").lowercase()
  val arch =
    when (val arch = System.getProperty("os.arch")) {
      "amd64",
      "x86_64" -> "x86-64"
      "arm64" -> "aarch64"
      else -> arch
    }
  when {
    os.startsWith("linux") -> "linux-$arch"
    os.startsWith("mac") -> "darwin-$arch"
    os.startsWith("windows") -> "win32-$arch"
    else -> error("unsupported host OS: $os")
  }
}

val cargoBuildHost =
  tasks.register<CargoBuild>("cargoBuildHost") {
    description = "Builds the session library for this host."
    sources.from(rustSources)
    workspace = workspaceDir
    targets = mapOf(jnaPlatform to "")
    release = false
    environment = emptyMap()
    libraryFileName = hostLibraryName
    outputDir = layout.buildDirectory.dir("generated/session/hostLibs")
  }

val generateBindings =
  tasks.register<UniffiBindgen>("generateBindings") {
    description = "Generates the session's Kotlin bindings."
    library.from(cargoBuildHost.map { it.outputDir.file("$jnaPlatform/$hostLibraryName") })
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
    val hostTag =
      when {
        System.getProperty("os.name").lowercase().startsWith("mac") -> "darwin-x86_64"
        System.getProperty("os.name").lowercase().startsWith("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
      }
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
