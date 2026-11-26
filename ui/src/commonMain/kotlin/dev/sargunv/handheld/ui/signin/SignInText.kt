package dev.sargunv.handheld.ui.signin

import androidx.compose.ui.platform.Clipboard
import dev.sargunv.handheld.data.romm.RommException
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl

/**
 * The server address the user typed, as a URL to sign in to, or null when it isn't one. An address
 * without a scheme gets https.
 */
internal fun serverUrl(input: String): String? {
  val trimmed = input.trim().trimEnd('/')
  if (trimmed.isEmpty()) return null
  val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
  val url = parseUrl(withScheme) ?: return null
  if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
  if (url.host.isEmpty()) return null
  return withScheme
}

/**
 * A note for a server [url] that isn't encrypted, or null when it is. Plain HTTP is allowed, for a
 * server on a private network or a tailnet, but the user should know what it means. Sign-in
 * switches to https first when the server redirects there, so the note says so.
 */
internal fun unencryptedNote(url: String?): String? =
  if (url != null && parseUrl(url)?.protocol == URLProtocol.HTTP) {
    "This address isn't encrypted. If the server doesn't switch to https itself, your sign-in " +
      "is sent in the clear, so use it only on a network you trust."
  } else {
    null
  }

internal enum class SignInMethod {
  Code,
  Token,
}

/** What went wrong signing in to [baseUrl], in plain words. */
internal fun signInErrorMessage(error: Exception, baseUrl: String, method: SignInMethod): String =
  when (error) {
    is RommException.Unreachable ->
      "Couldn't reach $baseUrl. Check the address, and that this device is online."
    is RommException.Unauthorized,
    is RommException.Forbidden ->
      "The server didn't accept this token. Check that it's complete, and that it hasn't " +
        "expired or been deleted in RomM."
    is RommException.DeviceAuthExpired ->
      "The code expired before it was approved. Get a new code to try again."
    is RommException.DeviceAuthDenied -> "The sign-in was denied in RomM."
    is RommException.NotFound,
    is RommException.InvalidResponse ->
      when (method) {
        SignInMethod.Code ->
          "$baseUrl doesn't offer sign-in with a code. Check the address, or use an API token."
        SignInMethod.Token -> "$baseUrl doesn't look like a RomM server. Check the address."
      }
    is RommException.RateLimited -> "The server is busy. Wait a minute, then try again."
    is RommException.ServerError ->
      "The server had a problem (status ${error.status}). Try again later."
    else -> "Couldn't sign in: ${error.message}"
  }

/** The clipboard's text, or null when it holds none. */
internal expect suspend fun Clipboard.readText(): String?
