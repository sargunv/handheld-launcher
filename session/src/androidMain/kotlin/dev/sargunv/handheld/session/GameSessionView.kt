package dev.sargunv.handheld.session

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shows a [GameSession] in [VideoMode.WINDOW] and feeds it the gamepad's input while focused.
 *
 * The session runs only while the view's surface exists, its window has focus and [running] is
 * true, so it pauses when the app goes to the background or a system dialog or shade covers it. The
 * view never stops the session; its owner does.
 *
 * Pausing writes save memory to disk, so the view pauses and resumes sessions in order on a thread
 * of its own rather than the UI thread.
 */
class GameSessionView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  /** The session to show, or null for none. */
  var session: GameSession? = null
    set(value) {
      if (field === value) return
      field?.let { old ->
        control(old) { pause() }
        if (attached) old.detachWindow()
      }
      attached = false
      resumed = false
      field = value
      input = value?.let(::GamepadInput)
      val holder = holder
      if (value != null && holder.surface?.isValid == true) attach(value, holder)
      update()
    }

  /** Whether the session should run while it's visible and focused. */
  var running: Boolean = true
    set(value) {
      field = value
      update()
    }

  private var input: GamepadInput? = null
  private var attached = false
  private var resumed = false

  init {
    holder.addCallback(this)
    isFocusable = true
    isFocusableInTouchMode = true
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    session?.let { attach(it, holder) }
    update()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    session?.resizeWindow(width.toUInt(), height.toUInt())
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    // The session must let go of the surface before this returns.
    attached = false
    update()
    session?.detachWindow()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    update()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    update()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    input?.onKeyEvent(event) == true || super.dispatchKeyEvent(event)

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    input?.onMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

  private fun attach(session: GameSession, holder: SurfaceHolder) {
    val window = NativeWindows.handle(holder.surface) ?: return
    val frame = holder.surfaceFrame
    try {
      session.attachWindow(window, frame.width().toUInt(), frame.height().toUInt())
      attached = true
    } catch (e: SessionException) {
      Log.e(TAG, "can't present the session to its surface", e)
    }
  }

  private fun update() {
    val session = session ?: return
    val run = running && attached && hasWindowFocus() && isAttachedToWindow
    if (run == resumed) return
    resumed = run
    if (!run) input?.releaseAll()
    control(session) { if (run) resume() else pause() }
  }

  private companion object {
    const val TAG = "GameSessionView"

    /** Runs pauses and resumes in the order the views ask for them. */
    val controlThread: ExecutorService = Executors.newSingleThreadExecutor {
      Thread(it, "session-control").apply { isDaemon = true }
    }

    fun control(session: GameSession, action: GameSession.() -> Unit) {
      controlThread.execute {
        try {
          session.action()
        } catch (e: SessionException) {
          Log.e(TAG, "the session failed to pause", e)
        } catch (_: IllegalStateException) {
          // The owner closed the session first, which wrote its save memory.
        }
      }
    }
  }
}
