package dev.sargunv.handheld.launch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import dev.sargunv.handheld.achievements.toGameAchievements
import dev.sargunv.handheld.data.writeAtomically
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.session.CapturedFrame
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.session.SessionException
import dev.sargunv.handheld.ui.play.GameAchievements
import dev.sargunv.handheld.ui.play.QuickMenuSession
import dev.sargunv.handheld.ui.play.StateSlot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * The quick menu's hold on a game in the built-in session: its save states, one file per slot in
 * [statePaths], each with a picture of the game beside it, and the session's speed.
 *
 * @param session the running session, or null once it has stopped.
 */
internal class SessionQuickMenu(
  private val session: () -> GameSession?,
  private val statePaths: List<String>,
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
        running.loadState(path)
        null
      } catch (e: SessionException) {
        Log.e(TAG, "can't load the state in $path", e)
        failure(e)
      }
    }

  override fun setFastForward(on: Boolean) {
    session()?.setFastForward(if (on) FAST_FORWARD_SPEED else 1u)
  }

  override fun resume() = onResume()

  override fun quit() = onQuit()

  /** Writes [frame] to [file] as a PNG, replacing it only once the new one is whole. */
  private fun writePng(frame: CapturedFrame, file: File) {
    val bitmap =
      Bitmap.createBitmap(frame.width.toInt(), frame.height.toInt(), Bitmap.Config.ARGB_8888)
    // ARGB_8888's bytes are in R, G, B, A order, as the frame's are.
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
    val png = ByteArrayOutputStream()
    try {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw IOException("not encoded")
    } finally {
      bitmap.recycle()
    }
    writeAtomically(Path(file.path), png.toByteArray())
  }

  private fun failure(e: SessionException): String =
    when (e) {
      is SessionException.StatesUnsupported,
      is SessionException.StatesNotPersistent -> "Can't save states"
      is SessionException.Serialize -> "Save state failed"
      is SessionException.Unserialize -> "Load state failed"
      is SessionException.Hardcore -> "Hardcore · No loading states"
      is SessionException.Io -> "State file error · ${e.message}"
      is SessionException.Stopped -> STOPPED
      else -> e.message ?: e.toString()
    }

  private companion object {
    const val TAG = "SessionQuickMenu"
    const val STOPPED = "Game stopped"

    /** How many times its own speed a game runs while fast forward is on. */
    const val FAST_FORWARD_SPEED = 4u
  }
}
