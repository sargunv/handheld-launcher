package dev.sargunv.handheld

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings as AndroidSettings
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.SecretStore
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.data.auth.KeystoreFile
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.launch.Emulators
import dev.sargunv.handheld.launch.PlaySaves
import dev.sargunv.handheld.launch.Plays
import dev.sargunv.handheld.session.rcheevosUserAgentClause
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's process, which holds one of each thing its activities and services share. */
class HandheldApp : Application() {
  val versionName: String by lazy {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
  }

  private val allFilesAccessGranted by lazy {
    MutableStateFlow(Environment.isExternalStorageManager())
  }

  /** Whether the app has all-files access, which games in shared storage need. */
  val allFilesAccess: StateFlow<Boolean>
    get() = allFilesAccessGranted

  /** Reads [allFilesAccess] again, as the user may be back from granting it. */
  fun refreshAllFilesAccess() {
    allFilesAccessGranted.value = Environment.isExternalStorageManager()
  }

  /** Opens the system page where the user grants the app all-files access, over [from]. */
  fun openAllFilesAccessSettings(from: Activity) {
    from.startActivity(
      Intent(
        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", packageName, null),
      ),
    )
  }

  private val launcherResumed = MutableStateFlow(false)

  /** Whether the launcher is resumed, which makes it what the first display shows. */
  internal val launcherInFront: StateFlow<Boolean> = launcherResumed.asStateFlow()

  /** Records whether the launcher is resumed, from its onResume and onPause. */
  internal fun setLauncherResumed(resumed: Boolean) {
    launcherResumed.value = resumed
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The settings, the games, the RomM sign-in, the download queue and save sync. The queue goes on
   * while the activity is gone, and [BackgroundWorkService] keeps the process alive while there's
   * work.
   */
  val data by lazy {
    HandheldData(
      credentials =
        KeystoreFile(
          File(filesDir, "credentials"),
          RommCredentials.serializer(),
          "romm-credentials",
        ),
      library =
        LibraryCache.open(getDatabasePath("library.db").also { it.parentFile?.mkdirs() }.path),
      dataDir = filesDir,
      cacheDir = cacheDir,
      settingsFile = File(filesDir, "settings.json"),
      storage = ::storageTargets,
      scope = scope,
      canWrite = allFilesAccess,
    )
  }

  /** The RetroAchievements account, encrypted with a key in the Android Keystore. */
  val retroAchievementsStore by lazy {
    SecretStore(
      KeystoreFile(
        File(filesDir, "retroachievements"),
        RetroAchievementsAccount.serializer(),
        "retroachievements-account",
      ),
    )
  }

  /** The client for RetroAchievements, which names the app's version, then rcheevos'. */
  val retroAchievementsClient by lazy {
    RetroAchievementsClient(
      RetroAchievementsClient.userAgent(versionName, rcheevosUserAgentClause()),
      data.http,
    )
  }

  /** The device's RetroAchievements sign-in. */
  val retroAchievementsSignIn by lazy {
    RetroAchievementsSignIn(retroAchievementsStore, retroAchievementsClient, scope)
  }

  /** The standalone emulators the launcher started. */
  internal val emulators by lazy { Emulators(this) }

  /** Syncs a game's save around a play. */
  internal val playSaves by lazy { PlaySaves(data.saveSync) }

  /** The plays, from the start of each launch until its notes are in. */
  internal val plays by lazy {
    Plays(scope, playSaves, emulators::close, launcherInFront, data.adopter.unfinished) {
      BackgroundWorkService.start(this)
    }
  }

  /** The mounted shared storage volumes, the device's own first, then any SD card. */
  internal fun storageTargets(): List<StorageTarget> =
    getSystemService(StorageManager::class.java)
      .storageVolumes
      .filter { it.state == Environment.MEDIA_MOUNTED }
      .mapNotNull { volume ->
        volume.directory?.let {
          // A card's UUID names it wherever it's mounted; the device's own storage has none.
          val id = if (volume.isPrimary) "primary" else volume.uuid ?: it.path
          StorageTarget(it.path, volume.getDescription(this), id)
        }
      }
}

internal val Context.handheldApp: HandheldApp
  get() = applicationContext as HandheldApp
