package dev.sargunv.handheld.session

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import co.touchlab.kermit.Logger

/**
 * Shows the bottom half of a [GameSession]'s frames, the lower screen of a two-screen system whose
 * core draws its screens stacked, on a second display, while the session's [GameSessionView] shows
 * the top half. Touches on it reach the session as libretro's pointer while [touchable].
 *
 * The session presents to it only while its surface exists, and shows the whole frame on the main
 * view again once it goes. It never pauses or resumes the session; the main view does.
 */
class LowerScreenView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  /** The session to show, or null for none. */
  var session: GameSession? = null
    set(value) {
      if (field === value) return
      field?.let { old ->
        if (attached) old.detachLowerWindow()
        old.releaseTouch()
      }
      attached = false
      field = value
      val holder = holder
      if (value != null && holder.surface?.isValid == true) attach(value, holder)
    }

  /** Whether touches reach the session, as they shouldn't while a menu covers the game. */
  var touchable: Boolean = true
    set(value) {
      field = value
      if (!value) session?.releaseTouch()
    }

  /**
   * Called when the session can't present to this view, so its owner can show the game elsewhere.
   */
  var onUnsupported: () -> Unit = {}

  private var attached = false

  init {
    holder.addCallback(this)
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    session?.let { attach(it, holder) }
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    // EGL follows an Android surface's size on its own.
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    // A finger held as the surface goes sends no release.
    session?.releaseTouch()
    // The session must let go of the surface before this returns.
    if (attached) session?.detachLowerWindow()
    attached = false
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val session = session?.takeIf { attached && touchable } ?: return false
    return session.touch(Screen.LOWER, this, event)
  }

  private fun attach(session: GameSession, holder: SurfaceHolder) {
    val window = NativeWindows.handle(holder.surface) ?: return
    val frame = holder.surfaceFrame
    try {
      session.attachLowerWindow(window, frame.width().toUInt(), frame.height().toUInt())
      attached = true
    } catch (e: SessionException) {
      log.e(e) { "Can't present the session's lower screen" }
      onUnsupported()
    }
  }
}

private val log = Logger.withTag("LowerScreenView")
