package dev.sargunv.handheld.data.cores

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** A value kept as JSON in the file at [path]. Callers serialize access to it. */
internal class JsonFile<T>(private val path: Path, private val serializer: KSerializer<T>) {
  /** The stored value, or null when there's none. */
  fun read(): T? {
    if (!SystemFileSystem.exists(path)) return null
    val text = SystemFileSystem.source(path).buffered().use { it.readString() }
    return json.decodeFromString(serializer, text)
  }

  fun write(value: T) {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    // Write a sibling and move it over the old file, so a crash never leaves half a file.
    val temp = Path("$path.tmp")
    SystemFileSystem.sink(temp).buffered().use {
      it.writeString(json.encodeToString(serializer, value))
    }
    SystemFileSystem.atomicMove(temp, path)
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
