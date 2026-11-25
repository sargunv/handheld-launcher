package dev.sargunv.handheld.data.romm

import kotlin.time.Duration

/** A request to a RomM server that failed. */
sealed class RommException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** The server couldn't be reached, or the connection broke. Retrying may help. */
  class Unreachable(cause: Throwable) :
    RommException("Couldn't reach the server: ${cause.message}", cause)

  /** The server sent something this client can't read. */
  class InvalidResponse(message: String, cause: Throwable? = null) : RommException(message, cause)

  /** The server answered with an error status. */
  sealed class Status(val status: Int, val detail: String?, message: String) :
    RommException(message)

  /** The token is missing, wrong, expired or revoked (401). Signing in again fixes it. */
  class Unauthorized(detail: String?) :
    Status(401, detail, "The server didn't accept the sign-in" + detail.suffix())

  /** The token lacks a scope the request needs (403). */
  class Forbidden(detail: String?) :
    Status(403, detail, "The server didn't allow this" + detail.suffix())

  /** What the request named doesn't exist on the server (404). */
  class NotFound(detail: String?) :
    Status(404, detail, "The server didn't find it" + detail.suffix())

  /** The client sent too many requests (429). */
  class RateLimited(
    detail: String?,
    /** How long the server asked the client to wait, when it said. */
    val retryAfter: Duration?,
  ) : Status(429, detail, "The server asked to slow down" + detail.suffix())

  /** The server failed (5xx). */
  class ServerError(status: Int, detail: String?) :
    Status(status, detail, "The server failed with status $status" + detail.suffix())

  /** Any other error status. */
  class ClientError(status: Int, detail: String?) :
    Status(status, detail, "The server rejected the request with status $status" + detail.suffix())

  /** The user denied a device sign-in on the server. */
  class DeviceAuthDenied : RommException("The sign-in was denied on the server")

  /** A device sign-in code expired before the user approved it. */
  class DeviceAuthExpired : RommException("The sign-in code expired")
}

private fun String?.suffix() = if (isNullOrBlank()) "" else ": $this"
