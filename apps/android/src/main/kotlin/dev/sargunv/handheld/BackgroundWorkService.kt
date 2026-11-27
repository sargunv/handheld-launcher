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
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.downloads.DownloadState
import dev.sargunv.handheld.data.downloads.UploadPhase
import dev.sargunv.handheld.data.downloads.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app running while games download, a game uploads or a play is pending, so they go on in
 * the background, and shows their progress in a notification. It stops once none is under way.
 */
class BackgroundWorkService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val downloads by lazy { handheldApp.data.downloads }
  private val uploads by lazy { handheldApp.data.uploads }
  private val plays by lazy { handheldApp.plays }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, "Downloads and save sync", NotificationManager.IMPORTANCE_LOW),
    )
    startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    @OptIn(FlowPreview::class)
    scope.launch {
      // The system limits how often an app updates a notification.
      merge(downloads.queue, downloads.progress, uploads.upload, plays.pending, plays.sends)
        .sample(1000)
        .collect {
          // Read now rather than from the sample, so work that started since keeps the service.
          if (
            downloads.queue.value.none { it.isActive } &&
              uploads.upload.value?.isRunning != true &&
              plays.pending.value.isEmpty()
          ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          } else {
            manager.notify(NOTIFICATION, notification())
          }
        }
    }
  }

  /** Called once a data sync service has run six hours in a day, on Android 15 and later. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    log.w { "Out of foreground service time; stopping" }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val queue = downloads.queue.value
    val current = queue.firstOrNull { it.state == DownloadState.Downloading }
    val upload = uploads.upload.value?.takeIf { it.isRunning }
    val play = plays.pending.value.lastOrNull()
    val builder =
      Notification.Builder(this, CHANNEL)
        .setOngoing(true)
        .setContentIntent(
          PendingIntent.getActivity(
            this,
            0,
            launcherIntent(),
            PendingIntent.FLAG_IMMUTABLE,
          ),
        )
    when {
      current != null -> {
        val (bytes, total) = current.progressIn(downloads.progress.value)
        val waiting = queue.count { it.isActive }
        builder
          .setSmallIcon(android.R.drawable.stat_sys_download)
          .setContentTitle(current.title)
          .setContentText(
            "${formatSize(bytes)} of ${formatSize(total)}" +
              if (waiting > 1) " · ${waiting - 1} more queued" else "",
          )
          .setProgress(1000, (bytes * 1000 / total.coerceAtLeast(1)).toInt(), false)
      }
      upload != null -> {
        val percent = (upload.phase as? UploadPhase.Sending)?.percent
        builder
          .setSmallIcon(android.R.drawable.stat_sys_upload)
          .setContentTitle(upload.game.title)
          .setContentText(
            if (upload.phase is UploadPhase.Sending) "Uploading" else "Adding to your library",
          )
          .setProgress(100, percent ?: 0, percent == null)
      }
      play != null ->
        builder
          .setSmallIcon(android.R.drawable.stat_notify_sync)
          .setContentTitle(play)
          .setContentText(if (plays.sends.value > 0) "Syncing save" else "Playing")
      else ->
        builder
          .setSmallIcon(android.R.drawable.stat_sys_download)
          .setContentTitle("Waiting to download")
    }
    return builder.build()
  }

  companion object {
    private const val CHANNEL = "background"
    private const val NOTIFICATION = 1

    /** Starts the service, which stops itself once nothing is downloading, uploading or pending. */
    fun start(context: Context) {
      context.startForegroundService(Intent(context, BackgroundWorkService::class.java))
    }
  }
}

private val log = Logger.withTag("BackgroundWork")
