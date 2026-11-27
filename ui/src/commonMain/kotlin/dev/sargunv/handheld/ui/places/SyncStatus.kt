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
}
