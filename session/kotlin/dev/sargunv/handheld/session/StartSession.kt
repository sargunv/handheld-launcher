package dev.sargunv.handheld.session

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Starts a session as [GameSession.start] does, off the calling thread. Starting can't be abandoned
 * halfway: a caller cancelled meanwhile still waits, and the session that started is closed.
 */
suspend fun startSession(options: SessionOptions, listener: SessionListener): GameSession {
  var started: GameSession? = null
  try {
    return withContext(Dispatchers.IO + NonCancellable) {
      GameSession.start(options, listener).also { started = it }
    }
  } catch (e: CancellationException) {
    started?.let { withContext(Dispatchers.IO + NonCancellable) { it.close() } }
    throw e
  }
}
