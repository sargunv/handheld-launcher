package dev.sargunv.handheld.data

import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.LocalGameState
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.downloads.UploadManager
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.data.saves.BuiltInSaveFolders
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.data.settings.SettingsStore
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.PerGameState
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

/**
 * The settings, the games on the device, the sign-in to RomM, the download queue, uploads and save
 * sync, for as long as [scope] runs.
 *
 * @param credentials where the RomM sign-in is kept.
 * @param dataDir where the download queue, the content scan's cache, save sync's records and the
 *   built-in session's saves go.
 * @param cacheDir where save sync stages the saves it downloads.
 * @param settingsFile where the settings are kept.
 * @param storage lists the mounted storage targets, the device's own first.
 * @param canWrite whether the app may write to the storage targets now.
 * @param engine the HTTP engine of the app's one [http] client.
 */
class HandheldData(
  credentials: Vault<RommCredentials>,
  val library: LibraryCache,
  dataDir: File,
  cacheDir: File,
  settingsFile: File,
  storage: () -> List<StorageTarget>,
  private val scope: CoroutineScope,
  canWrite: Flow<Boolean> = flowOf(true),
  engine: HttpClientEngine = OkHttp.create(),
) {
  /** The app's one HTTP client, which everything that goes online shares. */
  val http = httpClient(engine)

  val settings = SettingsStore(settingsFile, scope)

  val games = DeviceGames(library, storage, scope, File(dataDir, "local-content.json"))

  val romm = RommSession(SecretStore(credentials), library, File(dataDir, "save-sync"), scope, http)

  val downloads =
    DownloadManager(
      queueFile = File(dataDir, "downloads.json"),
      library = library,
      games = games,
      romm = romm.state,
      storage = storage,
      scope = scope,
      canWrite = canWrite,
      storageSettings = { settings.settings.value.storage },
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
   * The game [key] names: a ROM from the library, or a local game once the scan of the platforms'
   * folders has found it. Null when there's no such game, such as a file that's gone or now matches
   * a ROM.
   */
  suspend fun game(key: GameKey): LibraryGame? =
    when (key) {
      is GameKey.Romm -> library.rom(key.romId)
      is GameKey.Local ->
        (games.localGame(key.path).first { it != null } as? LocalGameState.Local)?.game
    }

  /**
   * The signed-in account's save sync, or null while signed out. There's one for each account, so
   * its syncs run one at a time.
   */
  val saveSync: StateFlow<SaveSync?> =
    romm.state
      .map { account ->
        (account as? RommAccount)?.let {
          SaveSync(it.client, it.saveRecords, it.saveHistory, File(cacheDir, "save-staging"))
        }
      }
      .stateIn(scope, SharingStarted.Eagerly, null)

  /** The built-in session's save folders, with the signed-in account's save history. */
  val builtInSaveFolders =
    BuiltInSaveFolders(BuiltInSaves(File(dataDir, "saves").path)) {
      (romm.state.first { it != RommState.Unknown } as? RommAccount)?.saveHistory
    }

  /** What's kept per game, the settings and the built-in saves, handed to each file's ROM. */
  val adopter =
    Adopter(
      games.adoptions,
      PerGameState.of(
        PerGameState {
          settings.update { s -> s.adopted(it) }
          emptyList()
        },
        builtInSaveFolders,
      ),
      scope,
    )
}
