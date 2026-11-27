package dev.sargunv.handheld.ui.places

/** How the library's latest sync with the server went. */
enum class SyncStatus {
  NotStarted,
  /** Syncing into an empty cache, which fills in a piece at a time. */
  FirstSync,
  Syncing,
  Synced,
  /** The server couldn't be reached, or failed. */
  Failed,
  /** The server refused the device's token, so syncing waits for a new sign-in. */
  Unauthorized;

  /** Whether the sync has finished, one way or another. */
  val isDone: Boolean
    get() = this == Synced || this == Failed || this == Unauthorized

  val label: String
    get() =
      when (this) {
        NotStarted -> "Not synced"
        FirstSync,
        Syncing -> "Syncing"
        Synced -> "Synced"
        Failed -> "Offline"
        Unauthorized -> "Sign-in refused"
      }

  /** What a list with nothing in it says: [synced] once synced, otherwise [label]. */
  fun emptyLabel(synced: String): String =
    when (this) {
      // The first sync starts as soon as the app opens.
      NotStarted -> Syncing.label
      Synced -> synced
      else -> label
    }
}
