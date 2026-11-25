package dev.sargunv.handheld.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Settings kept as JSON in the file at [path], so they survive restarts.
 *
 * A setting missing from the file, or one whose stored value this version doesn't know, takes its
 * default from the settings class, so adding a setting with a default needs nothing else.
 */
class SettingsFile<T>(
  private val path: Path,
  private val serializer: KSerializer<T>,
  private val default: T,
) {
  private val mutex = Mutex()

  /**
   * The stored settings, or [default] when there's no file or it can't be read. It blocks, but
   * reads a small file, so the first frame can already use the stored settings.
   */
  fun read(): T {
    if (!SystemFileSystem.exists(path)) return default
    return try {
      val text = SystemFileSystem.source(path).buffered().use { it.readString() }
      json.decodeFromString(serializer, text)
    } catch (_: IOException) {
      default
    } catch (_: SerializationException) {
      default
    }
  }

  /** Replaces the stored settings with [settings]. */
  suspend fun write(settings: T) {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        // Write a sibling and move it over the old file, so a crash never leaves half a file.
        val temp = Path("$path.tmp")
        SystemFileSystem.sink(temp).buffered().use {
          it.writeString(json.encodeToString(serializer, settings))
        }
        SystemFileSystem.atomicMove(temp, path)
      }
    }
  }

  private companion object {
    val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = true
      coerceInputValues = true
    }
  }
}
