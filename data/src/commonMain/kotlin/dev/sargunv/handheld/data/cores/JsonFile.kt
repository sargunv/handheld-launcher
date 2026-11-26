package dev.sargunv.handheld.data.cores

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** A value kept as JSON in the file at [path]. Callers serialize access to it. */
internal class JsonFile<T>(
  private val path: Path,
  private val serializer: KSerializer<T>,
  private val json: Json = DefaultJson,
) {
  /** The stored value, or null when there's none. */
  fun read(): T? {
    if (!SystemFileSystem.exists(path)) return null
    val text = SystemFileSystem.source(path).buffered().use { it.readString() }
    return json.decodeFromString(serializer, text)
  }

  fun write(value: T) =
    writeAtomically(path, json.encodeToString(serializer, value).encodeToByteArray())

  private companion object {
    val DefaultJson = Json { ignoreUnknownKeys = true }
  }
}

/**
 * Writes [bytes] to [path] by way of a sibling moved over it, so a crash never leaves half a file.
 */
internal fun writeAtomically(path: Path, bytes: ByteArray) {
  path.parent?.let { SystemFileSystem.createDirectories(it) }
  val temp = Path("$path.tmp")
  SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
  SystemFileSystem.atomicMove(temp, path)
}
