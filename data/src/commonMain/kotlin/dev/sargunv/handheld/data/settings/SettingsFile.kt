package dev.sargunv.handheld.data.settings

import dev.sargunv.handheld.data.cores.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Settings kept as JSON in the file at [path], so they survive restarts.
 *
 * A setting missing from the file, or one whose stored value this version doesn't know, takes its
 * default from the settings class, so adding a setting with a default needs nothing else.
 */
class SettingsFile<T>(path: Path, serializer: KSerializer<T>, private val default: T) {
  private val file = JsonFile(path, serializer, json)
  private val mutex = Mutex()

  /**
   * The stored settings, or [default] when there's no file or it can't be read. It blocks, but
   * reads a small file, so the first frame can already use the stored settings.
   */
  fun read(): T =
    try {
      file.read() ?: default
    } catch (_: IOException) {
      default
    } catch (_: SerializationException) {
      default
    }

  /**
   * Replaces the stored settings with [settings]. When storage fails, as on a full disk, the old
   * file stays and the app carries on with the new settings until it closes.
   */
  suspend fun write(settings: T) {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          file.write(settings)
        } catch (_: IOException) {}
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
