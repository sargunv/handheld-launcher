package dev.sargunv.handheld.session

import android.content.Context
import android.hardware.input.InputManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import co.touchlab.kermit.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shows a [GameSession] and feeds it the gamepad's input while focused, and touches while it runs,
 * as libretro's pointer.
 *
 * The session runs only while the view's surface exists, its window is visible and [running] is
 * true, so it pauses when the app goes to the background or the screen turns off. The view never
 * stops the session; its owner does.
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
        control(old, resume = false)
        if (attached) old.detachWindow(Screen.MAIN)
      }
      attached = false
      resumed = false
      field?.releaseTouch()
      field = value
      input?.close()
      input = value?.let {
        GamepadInput(it, context.getSystemService(InputManager::class.java), aIsEast)
      }
      val holder = holder
      if (value != null && holder.surface?.isValid == true) attach(value, holder)
      update()
    }

  /** Whether the gamepad's A button is on the east of its face buttons, for the next [session]. */
  var aIsEast = false

  /** Whether the session should run while it's visible. */
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
    // The system marks a view focused by a key with a translucent wash, which would cover the game.
    defaultFocusHighlightEnabled = false
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    session?.let { attach(it, holder) }
    update()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    session?.resizeWindow(Screen.MAIN, width.toUInt(), height.toUInt())
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    // The session must let go of the surface before this returns.
    attached = false
    update()
    session?.detachWindow(Screen.MAIN)
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    // Controls released while another window has focus send this view no event.
    if (!hasWindowFocus) input?.releaseAll()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    update()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    update()
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val session = session ?: return false
    if (!resumed) return false
    return session.touch(Screen.MAIN, this, event)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    input?.onKeyEvent(event) == true || super.dispatchKeyEvent(event)

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    input?.onMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

  private fun attach(session: GameSession, holder: SurfaceHolder) {
    val window = NativeWindows.handle(holder.surface) ?: return
    val frame = holder.surfaceFrame
    try {
      session.attachWindow(Screen.MAIN, window, frame.width().toUInt(), frame.height().toUInt())
      attached = true
    } catch (e: SessionException) {
      log.e(e) { "Can't present the session to its surface" }
    }
  }

  private fun update() {
    val session = session ?: return
    val run = running && attached && isAttachedToWindow && windowVisibility == VISIBLE
    if (run == resumed) return
    resumed = run
    if (!run) {
      input?.releaseAll()
      session.releaseTouch()
    }
    control(session, resume = run)
  }

  private companion object {
    /** Runs pauses and resumes in the order the views ask for them. */
    val controlThread: ExecutorService = Executors.newSingleThreadExecutor {
      Thread(it, "session-control").apply { isDaemon = true }
    }

    fun control(session: GameSession, resume: Boolean) {
      controlThread.execute {
        try {
          if (resume) session.resume() else session.pause()
        } catch (_: SessionException.Stopped) {
          // The core quit, and the session stopped as it does when told to.
        } catch (e: SessionException) {
          log.e(e) { "The session failed to ${if (resume) "resume" else "pause"}" }
        }
      }
    }
  }
}

private val log = Logger.withTag("GameSessionView")

/**
 * Passes [event], a touch on [view], which shows the session on [screen], to the session as its
 * pointer. Like the resistive screens of the DS and 3DS, which these games were made for, two
 * fingers at once press at the point between them: the pointer is at the middle of every finger
 * down, and held while any is.
 */
internal fun GameSession.touch(screen: Screen, view: View, event: MotionEvent): Boolean {
  // A lifting finger is still in the event, but no longer presses.
  val lifting =
    when (event.actionMasked) {
      MotionEvent.ACTION_POINTER_UP -> event.actionIndex
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> -1
      MotionEvent.ACTION_DOWN,
      MotionEvent.ACTION_POINTER_DOWN,
      MotionEvent.ACTION_MOVE -> null
      else -> return true
    }
  val pressing = (0 until event.pointerCount).filter { it != lifting }
  val pressed = lifting != -1 && pressing.isNotEmpty()
  // A release stays where the last fingers were.
  val at = if (pressed) pressing else (0 until event.pointerCount).toList()
  touch(
    screen,
    at.map { event.getX(it) }.average().toFloat(),
    at.map { event.getY(it) }.average().toFloat(),
    view.width.toUInt(),
    view.height.toUInt(),
    pressed,
  )
  return true
}
