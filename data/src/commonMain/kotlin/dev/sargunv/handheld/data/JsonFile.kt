package dev.sargunv.handheld.data

import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The JSON of the app's own files. A field it doesn't know is skipped, and a value it doesn't know
 * takes the field's default, so a file from another version still reads.
 */
internal val FileJson = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
  encodeDefaults = true
}

/**
 * A value kept as JSON in [file]. A read sees the whole of one write. A file that can't be read or
 * decoded reads as no value, so the next write replaces it. Its calls block, and callers that write
 * from several coroutines take turns themselves.
 */
class JsonFile<T : Any>(private val file: File, private val serializer: KSerializer<T>) : Vault<T> {
  /** The stored value, or null when there's none. */
  override fun read(): T? =
    try {
      FileJson.decodeFromString(serializer, file.readText())
    } catch (_: FileNotFoundException) {
      null
    } catch (e: IOException) {
      unreadable(e)
    } catch (e: SerializationException) {
      unreadable(e)
    }

  private fun unreadable(e: Exception): T? {
    log.w(e) { "Couldn't read $file" }
    return null
  }

  override fun write(value: T) =
    writeAtomically(file, FileJson.encodeToString(serializer, value).encodeToByteArray())

  override fun delete() {
    file.delete()
  }
}

/**
 * Writes [bytes] to [file] by way of a hidden sibling moved over it, so nothing ever sees half a
 * file.
 */
fun writeAtomically(file: File, bytes: ByteArray) {
  val dir = file.absoluteFile.parentFile.apply { mkdirs() }
  val temp = Files.createTempFile(dir.toPath(), ".${file.name}.", ".tmp")
  try {
    Files.write(temp, bytes)
    Files.move(temp, file.toPath(), ATOMIC_MOVE)
  } finally {
    Files.deleteIfExists(temp)
  }
}

private val log = Logger.withTag("JsonFile")
