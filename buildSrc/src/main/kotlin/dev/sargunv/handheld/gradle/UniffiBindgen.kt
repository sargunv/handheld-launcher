package dev.sargunv.handheld.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Generates the session's Kotlin bindings from a build of its library. */
abstract class UniffiBindgen @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val library: ConfigurableFileCollection
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val config: ConfigurableFileCollection
  /** The bindgen crate's sources, which `cargo run` builds. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection
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
