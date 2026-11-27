package dev.sargunv.handheld.data.romm

/** A request to a RomM server that failed. */
sealed class RommException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** The server couldn't be reached, or the connection broke. Retrying may help. */
  class Unreachable(cause: Throwable) : RommException("RomM unreachable", cause)

  /** The server sent something this client can't read. */
  class InvalidResponse(message: String, cause: Throwable? = null) : RommException(message, cause)

  /** The server answered with an error status. Its message is the server's detail, if any. */
  sealed class Status(val status: Int, detail: String?, fallback: String) :
    RommException(detail?.takeUnless { it.isBlank() } ?: fallback)

  /** The token is missing, wrong, expired or revoked (401). Signing in again fixes it. */
  class Unauthorized(detail: String?) : Status(401, detail, "Sign-in refused")

  /** The token lacks a scope the request needs (403). */
  class Forbidden(detail: String?) : Status(403, detail, "Not allowed")

  /** What the request named doesn't exist on the server (404). */
  class NotFound(detail: String?) : Status(404, detail, "Not found")

  /** The client sent too many requests (429). */
  class RateLimited(detail: String?) : Status(429, detail, "Too many requests")

  /** The server failed (5xx). */
  class ServerError(status: Int, detail: String?) : Status(status, detail, "Server error $status")

  /** Any other error status. */
  class ClientError(status: Int, detail: String?) :
    Status(status, detail, "Request refused $status")

  /** The user denied a device sign-in on the server. */
  class DeviceAuthDenied : RommException("Sign-in denied")

  /** A device sign-in code expired before the user approved it. */
  class DeviceAuthExpired : RommException("Sign-in code expired")
}
