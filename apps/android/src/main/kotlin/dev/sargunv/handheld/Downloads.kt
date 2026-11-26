package dev.sargunv.handheld

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import dev.sargunv.handheld.data.auth.KeystoreCredentialStore
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

private var openDownloads: DownloadManager? = null

/**
 * Whether the app has all-files access, which writing games to shared storage needs. The activity
 * updates it when it comes back from the settings page that grants it.
 */
internal val allFilesAccess = MutableStateFlow(Environment.isExternalStorageManager())

/**
 * The download queue. It lives as long as the process, so downloads go on while the activity is
 * gone, and [DownloadService] keeps the process alive while there's work.
 */
@Synchronized
internal fun downloadManager(context: Context): DownloadManager {
  openDownloads?.let {
    return it
  }
  val app = context.applicationContext
  return DownloadManager(
      queueFile = Path(app.filesDir.path, "downloads.json"),
      library = libraryCache(app),
      credentials = credentialStore(app),
      storage = { storageTargets(app) },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      canWrite = allFilesAccess,
      // Read when a download starts, so it follows what Settings stored last.
      storageSettings = { settingsFile(app).read().storage },
    )
    .also { openDownloads = it }
}

/** Where the signed-in server's credentials live. */
internal fun credentialStore(context: Context) =
  KeystoreCredentialStore(File(context.applicationContext.filesDir, "credentials"))

/** The mounted shared storage volumes, the device's own first, then any SD card. */
private fun storageTargets(context: Context): List<StorageTarget> =
  context
    .getSystemService(StorageManager::class.java)
    .storageVolumes
    .filter { it.state == Environment.MEDIA_MOUNTED }
    .mapNotNull { volume ->
      volume.directory?.let {
        // A card's UUID names it wherever it's mounted; the device's own storage has none.
        val id = if (volume.isPrimary) "primary" else volume.uuid ?: it.path
        StorageTarget(it.path, volume.getDescription(context), id)
      }
    }
