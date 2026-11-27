package dev.sargunv.handheld.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Builds the session library into [outputDir]: a debug build for the host, or, with [abis], a
 * release build for each ABI with cargo-ndk, in a directory per ABI.
 */
abstract class CargoBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection
  @get:Internal abstract val workspace: DirectoryProperty
  @get:Input abstract val abis: ListProperty<String>
  @get:Input @get:Optional abstract val minSdk: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun build() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    if (abis.get().isEmpty()) hostBuild(out) else ndkBuild(out)
  }

  private fun hostBuild(out: File) {
    cargo("build", "--package", "handheld-session", "--lib")
    val library = System.mapLibraryName("handheld_session")
    workspace.get().file("target/debug/$library").asFile.copyTo(out.resolve(library))
  }

  private fun ndkBuild(out: File) {
    cargo(
      "ndk",
      *abis.get().flatMap { listOf("--target", it) }.toTypedArray(),
      "--platform",
      minSdk.get(),
      "--output-dir",
      out.absolutePath,
      "build",
      "--package",
      "handheld-session",
      "--lib",
      "--release",
    )
  }

  private fun cargo(vararg args: String) {
    exec.exec {
      workingDir = workspace.get().asFile
      commandLine("cargo", *args)
    }
  }
}
