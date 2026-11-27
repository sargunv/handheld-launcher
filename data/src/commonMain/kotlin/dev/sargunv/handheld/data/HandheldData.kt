package dev.sargunv.handheld.data

import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DeviceGames
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageSettings
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.romm.RommSession
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path

/**
 * The games on the device, the sign-in to RomM and the download queue, for as long as [scope] runs.
 *
 * @param dataDir where the download queue, the content scan's cache and save sync's records go.
 * @param storage lists the mounted storage targets, the device's own first.
 * @param canWrite whether the app may write to the storage targets now.
 * @param storageSettings which storage targets new downloads may go to, and in what order.
 * @param engine makes the HTTP engine for each sign-in's client.
 */
class HandheldData(
  credentials: CredentialStore,
  val library: LibraryCache,
  dataDir: Path,
  storage: () -> List<StorageTarget>,
  scope: CoroutineScope,
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
}
