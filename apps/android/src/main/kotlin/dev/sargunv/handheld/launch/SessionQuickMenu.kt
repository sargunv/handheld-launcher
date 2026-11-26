package dev.sargunv.handheld.launch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import dev.sargunv.handheld.achievements.toGameAchievements
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.session.CapturedFrame
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.session.SessionException
import dev.sargunv.handheld.ui.play.GameAchievements
import dev.sargunv.handheld.ui.play.QuickMenuSession
import dev.sargunv.handheld.ui.play.StateSlot
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The quick menu's hold on a game in the built-in session: its save states, one file per slot in
 * [statePaths], each with a picture of the game beside it, and the session's speed.
 *
 * A state holds the game's save memory too, which loading it puts back, and the session then writes
 * to [saveRamPath]. So before a load, the save file as it is goes to a backup beside it, as
 * `<name>.<unix time>.bak`, the way the session keeps a save it replaces: an in-game save made
 * since the state isn't lost.
 *
 * @param session the running session, or null once it has stopped.
 */
internal class SessionQuickMenu(
  private val session: () -> GameSession?,
  private val statePaths: List<String>,
  private val saveRamPath: String?,
  override val hardcore: Boolean,
  private val onResume: () -> Unit,
  private val onQuit: () -> Unit,
) : QuickMenuSession {
  override suspend fun achievements(): GameAchievements =
    withContext(Dispatchers.IO) {
      try {
        session()?.achievements()?.toGameAchievements() ?: GameAchievements.Off
      } catch (_: SessionException) {
        GameAchievements.Off
      } catch (_: IllegalStateException) {
        GameAchievements.Off
      }
    }

  override suspend fun slots(): List<StateSlot> =
    withContext(Dispatchers.IO) {
      statePaths.mapIndexed { index, path ->
        val state = File(path)
        if (!state.isFile) return@mapIndexed StateSlot(index + 1)
        val thumbnail = BitmapFactory.decodeFile(BuiltInSaves.thumbnailOf(path))
        StateSlot(
          index + 1,
          savedAt = Instant.fromEpochMilliseconds(state.lastModified()),
          thumbnail = thumbnail?.asImageBitmap(),
        )
      }
    }

  override suspend fun saveState(slot: Int): String? =
    withContext(Dispatchers.IO) {
      val path = statePaths[slot - 1]
      val running = session() ?: return@withContext STOPPED
      try {
        File(path).parentFile?.mkdirs()
        running.saveState(path)
      } catch (e: SessionException) {
        Log.e(TAG, "can't save a state to $path", e)
        return@withContext failure(e)
      } catch (_: IllegalStateException) {
        return@withContext STOPPED
      }
      // The picture comes second: a state without one still loads.
      val thumbnail = File(BuiltInSaves.thumbnailOf(path))
      try {
        val frame = running.captureFrame()
        if (frame == null) thumbnail.delete() else writePng(frame, thumbnail)
      } catch (e: Exception) {
        Log.w(TAG, "can't keep a picture of the state in $path", e)
        thumbnail.delete()
      }
      null
    }

  override suspend fun loadState(slot: Int): String? =
    withContext(Dispatchers.IO) {
      val path = statePaths[slot - 1]
      val running = session() ?: return@withContext STOPPED
      try {
        // Pausing writes save memory, so the backup has the latest.
        running.pause()
        backUpSaveRam()
        running.loadState(path)
        null
      } catch (e: IOException) {
        Log.e(TAG, "can't back up the save before loading a state", e)
        "Couldn't back up the game's save, so the state wasn't loaded: ${e.message}"
      } catch (e: SessionException) {
        Log.e(TAG, "can't load the state in $path", e)
        failure(e)
      } catch (_: IllegalStateException) {
        STOPPED
      }
    }

  override fun setFastForward(on: Boolean) {
    try {
      session()?.setFastForward(if (on) FAST_FORWARD_SPEED else 1u)
    } catch (_: IllegalStateException) {
      // The session closed; there's nothing to speed up.
    }
  }

  override fun resume() = onResume()

  override fun quit() = onQuit()

  /**
   * Copies the save file to `<name>.<unix time>.bak` beside it, unless it's missing or the newest
   * such backup already holds the same bytes.
   */
  private fun backUpSaveRam() {
    val save = saveRamPath?.let(::File)?.takeIf { it.isFile } ?: return
    val bytes = save.readBytes()
    val backups =
      save.parentFile?.listFiles { file ->
        file.name.startsWith("${save.name}.") && file.name.endsWith(".bak")
      }
    val newest = backups?.maxByOrNull { it.lastModified() }
    if (newest != null && newest.readBytes().contentEquals(bytes)) return
    val time = System.currentTimeMillis() / 1000
    val backup =
      generateSequence(0) { it + 1 }
        .map { n -> File(save.parentFile, "${save.name}.$time${if (n == 0) "" else "-$n"}.bak") }
        .first { !it.exists() }
    val partial = File(save.parentFile, "${backup.name}.partial")
    try {
      partial.writeBytes(bytes)
      if (!partial.renameTo(backup)) throw IOException("can't write $backup")
    } finally {
      partial.delete()
    }
  }

  /** Writes [frame] to [file] as a PNG, replacing it only once the new one is whole. */
  private fun writePng(frame: CapturedFrame, file: File) {
    val bitmap =
      Bitmap.createBitmap(frame.width.toInt(), frame.height.toInt(), Bitmap.Config.ARGB_8888)
    // ARGB_8888's bytes are in R, G, B, A order, as the frame's are.
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
    val partial = File(file.parentFile, "${file.name}.partial")
    try {
      partial.outputStream().use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("not encoded")
      }
      if (!partial.renameTo(file)) throw IOException("can't replace $file")
    } finally {
      partial.delete()
      bitmap.recycle()
    }
  }

  private fun failure(e: SessionException): String =
    when (e) {
      is SessionException.StatesUnsupported,
      is SessionException.StatesNotPersistent -> "This game's core can't save states."
      is SessionException.Serialize -> "The core couldn't save the game's state."
      is SessionException.Unserialize -> "The core couldn't restore that state."
      is SessionException.Hardcore -> "Hardcore mode doesn't allow loading states."
      is SessionException.Io -> "Couldn't read or write the state: ${e.message}"
      else -> e.message ?: e.toString()
    }

  private companion object {
    const val TAG = "SessionQuickMenu"
    const val STOPPED = "The game has stopped."

    /** How many times its own speed a game runs while fast forward is on. */
    const val FAST_FORWARD_SPEED = 4u
  }
}
