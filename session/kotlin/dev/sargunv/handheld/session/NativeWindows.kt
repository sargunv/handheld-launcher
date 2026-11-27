package dev.sargunv.handheld.session

import android.view.Surface

/** The `ANativeWindow`s behind Android surfaces, for [GameSession.attachWindow]. */
internal object NativeWindows {
  init {
    // JNA loads the same library for the bindings; JNI needs it loaded through the JVM too.
    System.loadLibrary("handheld_session")
  }

  /**
   * The window behind [surface], with a reference that [GameSession.attachWindow] takes over, or
   * null if the surface has none.
   */
  fun handle(surface: Surface): ULong? = acquire(surface).takeIf { it != 0L }?.toULong()

  @JvmStatic private external fun acquire(surface: Surface): Long
}
