package dev.sargunv.handheld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.sargunv.handheld.data.downloads.Download
import dev.sargunv.handheld.data.downloads.DownloadProgress
import dev.sargunv.handheld.data.downloads.DownloadState
import dev.sargunv.handheld.data.downloads.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app running while games download, so the queue goes on in the background, and shows the
 * download's progress in a notification. It stops once nothing is waiting to download.
 */
class DownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
    )
    val downloads = handheldApp.downloadManager
    startForeground(
      NOTIFICATION,
      notification(downloads.queue.value, downloads.progress.value),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    @OptIn(FlowPreview::class)
    scope.launch {
      // The system limits how often an app updates a notification.
      val updates = combine(downloads.queue, downloads.progress, ::Pair).sample(1000)
      updates.collect { (queue, progress) ->
        if (queue.none { it.isActive }) {
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        } else {
          manager.notify(NOTIFICATION, notification(queue, progress))
        }
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun notification(
    queue: List<Download>,
    progress: Map<Long, DownloadProgress>,
  ): Notification {
    val current = queue.firstOrNull { it.state == DownloadState.Downloading }
    val waiting = queue.count { it.isActive }
    val builder =
      Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setContentIntent(
          PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
          ),
        )
    if (current == null) {
      return builder.setContentTitle("Waiting to download").build()
    }
    val (bytes, total) = progress[current.romId] ?: DownloadProgress(0, current.sizeBytes)
    return builder
      .setContentTitle(current.title)
      .setContentText(
        "${formatSize(bytes)} of ${formatSize(total)}" +
          if (waiting > 1) " · ${waiting - 1} more queued" else "",
      )
      .setProgress(1000, (bytes * 1000 / total.coerceAtLeast(1)).toInt(), false)
      .build()
  }

  companion object {
    private const val CHANNEL = "downloads"
    private const val NOTIFICATION = 1

    /** Starts the service, which stops itself once nothing is waiting to download. */
    fun start(context: Context) {
      context.startForegroundService(Intent(context, DownloadService::class.java))
    }
  }
}
