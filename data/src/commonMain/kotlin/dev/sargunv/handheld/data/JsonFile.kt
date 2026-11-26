package dev.sargunv.handheld.data

import kotlin.random.Random
import kotlin.random.nextULong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** JSON that skips the fields it doesn't know. */
internal val LenientJson = Json { ignoreUnknownKeys = true }

/**
 * A value kept as JSON in the file at [path]. Writes go one at a time, and a read sees the whole of
 * one of them.
 *
 * @param unreadableIsMissing whether a file that can't be read or decoded reads as no value, rather
 *   than throwing.
 */
internal class JsonFile<T : Any>(
  private val path: Path,
  private val serializer: KSerializer<T>,
  private val json: Json = LenientJson,
  private val unreadableIsMissing: Boolean = false,
) {
  private val mutex = Mutex()

  /** The stored value, or null when there's none. */
  suspend fun read(): T? = withContext(Dispatchers.IO) { readBlocking() }

  /** [read], on the caller's thread. */
  fun readBlocking(): T? =
    try {
      val text = SystemFileSystem.source(path).buffered().use { it.readString() }
      json.decodeFromString(serializer, text)
    } catch (_: FileNotFoundException) {
      null
    } catch (e: IOException) {
      if (unreadableIsMissing) null else throw e
    } catch (e: IllegalArgumentException) {
      if (unreadableIsMissing) null else throw e
    }

  suspend fun write(value: T) = locked { store(value) }

  /**
   * Replaces the stored value with what [change] makes of it, and returns the value it replaced.
   */
  suspend fun update(change: (T?) -> T): T? = locked {
    readBlocking().also { store(change(it)) }
  }

  suspend fun delete() = locked { SystemFileSystem.delete(path, mustExist = false) }

  private fun store(value: T) =
    writeAtomically(path, json.encodeToString(serializer, value).encodeToByteArray())

  private suspend fun <R> locked(block: () -> R): R =
    withContext(Dispatchers.IO) { mutex.withLock { block() } }
}

/**
 * Writes [bytes] to [path] by way of a hidden sibling moved over it, so nothing ever sees half a
 * file.
 */
fun writeAtomically(path: Path, bytes: ByteArray) {
  val dir = path.parent ?: Path(".")
  SystemFileSystem.createDirectories(dir)
  val temp = Path(dir, ".${path.name}.${Random.nextULong()}.tmp")
  try {
    SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
    SystemFileSystem.atomicMove(temp, path)
  } finally {
    SystemFileSystem.delete(temp, mustExist = false)
  }
}
