package dev.sargunv.handheld

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings as AndroidSettings
import dev.sargunv.handheld.data.auth.KeystoreCredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.saves.BuiltInSaveFolders
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.launch.Emulators
import dev.sargunv.handheld.launch.saveHistory
import dev.sargunv.handheld.ui.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

/** The app's process, which holds one of each thing its activities and services share. */
class HandheldApp : Application() {
  val versionName: String by lazy {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
  }

  /** Where the signed-in server's credentials live. */
  val credentials by lazy { KeystoreCredentialStore(File(filesDir, "credentials")) }

  /** The app's settings, in its private files directory. */
  val settingsFile by lazy {
    SettingsFile(Path(filesDir.resolve("settings.json").path), Settings.serializer(), Settings())
  }

  /** The library cache, in the app's databases directory. */
  val libraryCache by lazy {
    LibraryCache.open(getDatabasePath("library.db").also { it.parentFile?.mkdirs() }.path)
  }

  /**
   * Whether the app has all-files access, which writing games to shared storage needs. The launcher
   * updates it when it comes back from [allFilesAccessSettings].
   */
  val allFilesAccess by lazy { MutableStateFlow(Environment.isExternalStorageManager()) }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The games on the device's storage. */
  val deviceGames by lazy {
    DeviceGames(libraryCache, ::storageTargets, scope, Path(filesDir.path, "local-content.json"))
  }

  /**
   * The download queue. It goes on while the activity is gone, and [DownloadService] keeps the
   * process alive while there's work.
   */
  val downloadManager by lazy {
    DownloadManager(
      queueFile = Path(filesDir.path, "downloads.json"),
      library = libraryCache,
      games = deviceGames,
      credentials = credentials,
      storage = ::storageTargets,
      scope = scope,
      canWrite = allFilesAccess,
      // Read when a download starts, so it follows what Settings stored last.
      storageSettings = { settingsFile.read().storage },
    )
  }

  /** The standalone emulators the launcher started. */
  internal val emulators by lazy { Emulators(this) }

  /** The built-in session's save folders, in the app's files, with save sync's history. */
  val builtInSaveFolders by lazy {
    BuiltInSaveFolders(BuiltInSaves(File(filesDir, "saves").path), saveHistory(this))
  }

  /** The system page where the user grants the launcher all-files access. */
  fun allFilesAccessSettings(): Intent =
    Intent(
      AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
      Uri.fromParts("package", packageName, null),
    )

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
