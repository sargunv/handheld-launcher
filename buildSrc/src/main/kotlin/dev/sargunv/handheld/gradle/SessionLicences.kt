package dev.sargunv.handheld.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Writes the About screen's entries for what the game session builds in, in AboutLibraries' config
 * format, beside the entries every app shares: the Rust crates its Android library links, as
 * cargo-about finds them, each with the licence text from its own source, and the vendored C code,
 * whose entries are checked in, with rcheevos' text read from its LICENSE.
 */
abstract class SessionLicences @Inject constructor(private val exec: ExecOperations) :
  DefaultTask() {
  /** The workspace's manifests and lockfile, and cargo-about's config. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val manifests: ConfigurableFileCollection
  @get:Internal abstract val workspace: DirectoryProperty
  /** The Rust targets whose dependencies the session links. */
  @get:Input abstract val targets: ListProperty<String>
  /** Entries checked in: AboutLibraries' `libraries` and `licenses` directories. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val checkedIn: ConfigurableFileCollection
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val rcheevosLicence: RegularFileProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    for (dir in checkedIn) dir.copyRecursively(out)
    val libraries = out.resolve("libraries").apply { mkdirs() }
    val licenses = out.resolve("licenses").apply { mkdirs() }
    fun write(file: File, value: Any) =
      file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
    fun licence(hash: String, spdxId: String, name: String, text: String) =
      write(
        licenses.resolve("$hash.json"),
        mapOf("hash" to hash, "spdxId" to spdxId, "name" to name, "content" to text),
      )

    licence("rcheevos", "MIT", "MIT License", rcheevosLicence.get().asFile.readText())

    // Offline, so the crates' licence texts come from their packaged sources, which the fetch
    // downloads once. It fetches every platform's crates, since cargo-about reads the metadata of
    // the whole graph before it narrows it to the targets.
    val json = temporaryDir.resolve("crates.json")
    val root = workspace.get().asFile
    exec.exec {
      workingDir = root
      commandLine("cargo", "fetch", "--locked")
    }
    exec.exec {
      workingDir = root
      commandLine(
        "cargo",
        "about",
        "generate",
        "--format",
        "json",
        "--manifest-path",
        "session/Cargo.toml",
        "--offline",
        "--locked",
        "--fail",
        "--output-file",
        json.absolutePath,
        *targets.get().flatMap { listOf("--target", it) }.toTypedArray(),
      )
    }
    @Suppress("UNCHECKED_CAST")
    val about = JsonSlurper().parse(json) as Map<String, List<Map<String, Any?>>>
    // Each distinct licence text, by the crates it covers. A crate under several licences at once
    // has a text for each.
    val crateLicences = sortedMapOf<String, MutableList<String>>()
    val crates = sortedMapOf<String, Map<String, Any?>>()
    for (entry in about.getValue("licenses")) {
      val text = entry["text"] as String
      val spdxId = entry["id"] as String
      val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
      val hash = "crate-$spdxId-" + digest.take(4).joinToString("") { "%02x".format(it) }
      @Suppress("UNCHECKED_CAST")
      val users =
        (entry["used_by"] as List<Map<String, Any?>>).map { it["crate"] as Map<String, Any?> }
      // The session's own crate is the app itself.
      val dependencies = users.filter { it["source"] != null }
      if (dependencies.isEmpty()) continue
      licence(hash, spdxId, entry["name"] as String, text)
      for (crate in dependencies) {
        val id = "crates.io:${crate["name"]}@${crate["version"]}"
        crates[id] = crate
        crateLicences.getOrPut(id) { mutableListOf() } += hash
      }
    }
    for ((id, crate) in crates) {
      val name = crate["name"] as String
      val version = crate["version"] as String
      write(
        libraries.resolve("crate-$name-$version.json"),
        mapOf(
          "uniqueId" to id,
          "name" to name,
          "artifactVersion" to version,
          "description" to (crate["description"] as String?)?.trim().orEmpty(),
          "website" to
            (crate["repository"] ?: crate["homepage"] ?: "https://crates.io/crates/$name"),
          "licenses" to crateLicences.getValue(id).distinct(),
        ),
      )
    }
  }
}
