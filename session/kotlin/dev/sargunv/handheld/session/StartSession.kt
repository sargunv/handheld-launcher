package dev.sargunv.handheld.session

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Starts a session as [GameSession.start] does, off the calling thread. A caller cancelled
 * meanwhile still waits out the blocking start, and since withContext then throws, the session is
 * closed.
 */
suspend fun startSession(options: SessionOptions, listener: SessionListener): GameSession {
  var started: GameSession? = null
  try {
    return withContext(Dispatchers.IO) {
      GameSession.start(options, listener).also { started = it }
    }
  } catch (e: CancellationException) {
    started?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
    throw e
  }
}
