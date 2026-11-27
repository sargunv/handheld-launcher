package dev.sargunv.handheld.ui.places

import dev.sargunv.handheld.data.romm.SyncStatus

internal val SyncStatus.label: String
  get() =
    when (this) {
      SyncStatus.NotStarted -> "Not synced"
      SyncStatus.FirstSync,
      SyncStatus.Syncing -> "Syncing"
      SyncStatus.Synced -> "Synced"
      SyncStatus.Failed -> "Offline"
      SyncStatus.Unauthorized -> "Sign-in refused"
    }

/** What a list with nothing in it says: [synced] once synced, otherwise [label]. */
internal fun SyncStatus.emptyLabel(synced: String): String =
  when (this) {
    // The first sync starts as soon as the app opens.
    SyncStatus.NotStarted -> SyncStatus.Syncing.label
    SyncStatus.Synced -> synced
    else -> label
  }
