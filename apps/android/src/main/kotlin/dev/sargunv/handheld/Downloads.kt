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
import kotlinx.io.files.Path

private var openDownloads: DownloadManager? = null

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
      volume.directory?.let { StorageTarget(it.path, volume.getDescription(context)) }
    }
