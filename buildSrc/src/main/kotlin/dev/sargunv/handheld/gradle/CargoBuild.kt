package dev.sargunv.handheld.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

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
