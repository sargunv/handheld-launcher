package dev.sargunv.handheld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.downloads.UploadPhase
import dev.sargunv.handheld.data.downloads.formatSize
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app running while games download, a game uploads or a play isn't over, so they go on in
 * the background, and shows their progress in a notification. It stops once none is under way.
 */
class BackgroundWorkService : Service() {
  private val scope = MainScope()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val app = handheldApp
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, "Downloads and save sync", NotificationManager.IMPORTANCE_LOW),
    )
    startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    @OptIn(FlowPreview::class)
    scope.launch {
      // The system limits how often an app updates a notification.
      app.workChanges.sample(1.seconds).collect {
        // Read now rather than from the sample, so work that started since keeps the service.
        if (app.hasWork) manager.notify(NOTIFICATION, notification()) else stop()
      }
    }
  }

  /** Called once a data sync service has run six hours in a day, on Android 15 and later. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    log.w { "Out of foreground service time; stopping" }
    stop()
  }

  private fun stop() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val app = handheldApp
    val queue = app.data.downloads.queue.value
    val active = app.data.downloads.active.value
    val current = queue.find { it.romId == active?.romId }
    val upload = app.data.uploads.upload.value?.takeIf { it.isRunning }
    val play = app.plays.pending.value.lastOrNull()
    val builder =
      Notification.Builder(this, CHANNEL)
        .setOngoing(true)
        .setContentIntent(
          PendingIntent.getActivity(this, 0, launcherIntent(), PendingIntent.FLAG_IMMUTABLE),
        )
    when {
      active != null && current != null -> {
        val (bytes, total) = active.progress
        val waiting = queue.count { !it.failed }
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
          .setContentTitle(play.title)
          .setContentText(if (play.gameOver) "Syncing save" else "Playing")
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

    /**
     * Starts the service whenever work starts while [activity] is started, since an app in the
     * background can't start one. The service then stops itself once nothing is under way.
     */
    fun follow(activity: ComponentActivity) {
      val app = activity.handheldApp
      activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
          app.workChanges
            .map { app.hasWork }
            .distinctUntilChanged()
            .filter { it }
            .collect {
              activity.startForegroundService(Intent(activity, BackgroundWorkService::class.java))
            }
        }
      }
    }
  }
}

/** Whether games are downloading, a game uploading or a play isn't over. */
private val HandheldApp.hasWork: Boolean
  get() =
    data.downloads.queue.value.any { !it.failed } ||
      data.uploads.upload.value?.isRunning == true ||
      plays.pending.value.isNotEmpty()

/** Each change to the work, the running download's progress included. */
private val HandheldApp.workChanges: Flow<Unit>
  get() =
    merge(data.downloads.queue, data.downloads.active, data.uploads.upload, plays.pending).map {}

private val log = Logger.withTag("BackgroundWork")
