package dev.sargunv.handheld.data

import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.downloads.UploadManager
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.data.saves.BuiltInSaveFolders
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/**
 * The games on the device, the sign-in to RomM, the download queue, uploads and save sync, for as
 * long as [scope] runs.
 *
 * @param dataDir where the download queue, the content scan's cache, save sync's records and the
 *   built-in session's saves go.
 * @param cacheDir where save sync stages the saves it downloads.
 * @param storage lists the mounted storage targets, the device's own first.
 * @param canWrite whether the app may write to the storage targets now.
 * @param storageSettings which storage targets new downloads may go to, and in what order.
 * @param engine makes the HTTP engine for each sign-in's client.
 */
class HandheldData(
  credentials: CredentialStore,
  val library: LibraryCache,
  dataDir: Path,
  cacheDir: Path,
  storage: () -> List<StorageTarget>,
  private val scope: CoroutineScope,
  canWrite: Flow<Boolean> = flowOf(true),
  storageSettings: () -> StorageSettings = { StorageSettings() },
  engine: () -> HttpClientEngine = { OkHttp.create() },
) {
  val games = DeviceGames(library, storage, scope, Path(dataDir, "local-content.json"))

  val romm = RommSession(credentials, library, Path(dataDir, "save-sync"), scope, engine)

  val downloads =
    DownloadManager(
      queueFile = Path(dataDir, "downloads.json"),
      library = library,
      games = games,
      romm = romm.state,
      storage = storage,
      scope = scope,
      canWrite = canWrite,
      storageSettings = storageSettings,
    )

  val uploads = UploadManager(romm, scope)

  /**
   * Signs out of the server, and stops an upload that's sending. Unless [keepQueue], the downloads
   * not yet done leave the queue, and the server hears of each install it asked for that was
   * canceled.
   */
  fun signOut(keepQueue: Boolean) {
    val account = romm.account
    uploads.stop()
    if (!keepQueue && account != null) downloads.dropQueue(account)
    scope.launch { romm.signIn.signOut() }
  }

  /**
   * The signed-in account's save sync, or null while signed out. There's one for each account, so
   * its syncs run one at a time.
   */
  val saveSync: StateFlow<SaveSync?> =
    romm.state
      .map { account ->
        (account as? RommAccount)?.let {
          SaveSync(
            it.client,
            records = File(it.saveRecords.toString()),
            history = File(it.saveHistory.toString()),
            staging = File(cacheDir.toString(), "save-staging"),
          )
        }
      }
      .stateIn(scope, SharingStarted.Eagerly, null)

  /** The built-in session's save folders, with the signed-in account's save history. */
  val builtInSaveFolders =
    BuiltInSaveFolders(BuiltInSaves(Path(dataDir, "saves").toString())) {
      val account = romm.state.first { it != RommState.Unknown } as? RommAccount
      account?.let { File(it.saveHistory.toString()) }
    }
}
