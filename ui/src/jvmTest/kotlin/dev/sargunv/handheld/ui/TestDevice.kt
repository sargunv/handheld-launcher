package dev.sargunv.handheld.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.StorageTarget
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.io.path.createTempDirectory
import kotlinx.io.files.Path

internal val TestDevice = DeviceDescription("install-1", "Test device", "test", null)

/** A new, empty directory for covers. */
internal fun testCoverDir() = Path(createTempDirectory("handheld-covers").toString())

/** A download queue with no storage, in a new, empty directory. */
@Composable
internal fun rememberTestDownloads(
  library: LibraryCache,
  credentials: CredentialStore,
  storage: List<StorageTarget> = emptyList(),
  engine: () -> HttpClientEngine = { MockEngine { respondError(HttpStatusCode.NotFound) } },
): DownloadManager {
  val scope = rememberCoroutineScope()
  return remember {
    DownloadManager(
      queueFile = Path(createTempDirectory("handheld-downloads").toString(), "downloads.json"),
      library = library,
      credentials = credentials,
      storage = { storage },
      scope = scope,
      engine = engine,
    )
  }
}
