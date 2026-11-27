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
    is RommException.Unreachable -> "Unreachable · $baseUrl"
    is RommException.Unauthorized,
    is RommException.Forbidden -> "Token refused"
    is RommException.DeviceAuthExpired -> "Code expired"
    is RommException.DeviceAuthDenied -> "Sign-in denied"
    is RommException.NotFound,
    is RommException.InvalidResponse ->
      when (method) {
        SignInMethod.Code -> "No code sign-in · $baseUrl"
        SignInMethod.Token -> "Not a RomM server · $baseUrl"
      }
    is RommException.RateLimited -> "Server busy · Try again in a minute"
    is RommException.ServerError -> "Server error ${error.status}"
    else -> listOfNotNull("Sign-in failed", error.message).joinToString(" · ")
  }

/** The clipboard's text, or null when it holds none. */
internal expect suspend fun Clipboard.readText(): String?
