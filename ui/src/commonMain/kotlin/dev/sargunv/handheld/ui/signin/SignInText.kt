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
 * server on a private network or a tailnet.
 */
internal fun unencryptedNote(url: String?): String? =
  if (url != null && parseUrl(url)?.protocol == URLProtocol.HTTP) {
    "Unencrypted connection"
  } else {
    null
  }

internal enum class SignInMethod {
  Code,
  Token,
}

/** What went wrong signing in to [baseUrl]. */
internal fun signInErrorMessage(error: Exception, baseUrl: String, method: SignInMethod): String =
  when (error) {
    is RommException.Unreachable -> "Couldn't reach $baseUrl."
    is RommException.Unauthorized,
    is RommException.Forbidden -> "The server didn't accept this token."
    is RommException.DeviceAuthExpired -> "The code expired before it was approved."
    is RommException.DeviceAuthDenied -> "The sign-in was denied in RomM."
    is RommException.NotFound,
    is RommException.InvalidResponse ->
      when (method) {
        SignInMethod.Code -> "$baseUrl doesn't offer sign-in with a code."
        SignInMethod.Token -> "$baseUrl doesn't look like a RomM server."
      }
    is RommException.RateLimited -> "The server is busy. Try again in a minute."
    is RommException.ServerError -> "The server had a problem (status ${error.status})."
    else -> "Couldn't sign in: ${error.message}"
  }

/** The clipboard's text, or null when it holds none. */
internal expect suspend fun Clipboard.readText(): String?
