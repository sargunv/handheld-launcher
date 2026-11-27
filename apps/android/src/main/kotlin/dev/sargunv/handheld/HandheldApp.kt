package dev.sargunv.handheld

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings as AndroidSettings
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.auth.KeystoreCredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.data.saves.BuiltInSaveFolders
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import dev.sargunv.handheld.launch.Emulators
import dev.sargunv.handheld.ui.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

/** The app's process, which holds one of each thing its activities and services share. */
class HandheldApp : Application() {
  val versionName: String by lazy {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
  }

  /** The app's settings, in its private files directory. */
  val settingsFile by lazy {
    SettingsFile(Path(filesDir.resolve("settings.json").path), Settings.serializer(), Settings())
  }

  /**
   * Whether the app has all-files access, which writing games to shared storage needs. The launcher
   * updates it when it comes back from [allFilesAccessSettings].
   */
  val allFilesAccess by lazy { MutableStateFlow(Environment.isExternalStorageManager()) }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The games, the RomM sign-in and the download queue. The queue goes on while the activity is
   * gone, and [DownloadService] keeps the process alive while there's work.
   */
  val data by lazy {
    HandheldData(
      credentials = KeystoreCredentialStore(File(filesDir, "credentials")),
      library =
        LibraryCache.open(getDatabasePath("library.db").also { it.parentFile?.mkdirs() }.path),
      dataDir = Path(filesDir.path),
      storage = ::storageTargets,
      scope = scope,
      canWrite = allFilesAccess,
      // Read when a download starts, so it follows what Settings stored last.
      storageSettings = { settingsFile.read().storage },
    )
  }

  /** The standalone emulators the launcher started. */
  internal val emulators by lazy { Emulators(this) }

  /**
   * The signed-in account's save sync, or null while signed out. There's one for each account, so
   * its syncs run one at a time.
   */
  val saveSync: StateFlow<SaveSync?> by lazy {
    data.romm.state
      .map { account ->
        (account as? RommAccount)?.let {
          SaveSync(
            it.client,
            records = File(it.saveRecords.toString()),
            history = File(it.saveHistory.toString()),
            staging = File(cacheDir, "save-staging"),
          )
        }
      }
      .stateIn(scope, SharingStarted.Eagerly, null)
  }

  /** The built-in session's save folders, in the app's files, with the account's save history. */
  val builtInSaveFolders by lazy {
    BuiltInSaveFolders(BuiltInSaves(File(filesDir, "saves").path)) {
      val account = data.romm.state.first { it != RommState.Unknown } as? RommAccount
      account?.let { File(it.saveHistory.toString()) }
    }
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
