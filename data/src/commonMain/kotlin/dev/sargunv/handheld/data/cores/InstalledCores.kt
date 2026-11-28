package dev.sargunv.handheld.data.cores

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.decode
import dev.sargunv.handheld.data.writeTo
import dev.sargunv.handheld.domain.emulator.CoreSource
import dev.sargunv.handheld.domain.emulator.LibretroCore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A core installed on this device, and the build it came from. */
@Serializable
data class InstalledCore(
  /** The core's library name, such as `gambatte_libretro`. */
  val library: String,
  /** The release the build came from, for cores from GitHub releases. */
  val release: String? = null,
  /** The build's `ETag`, for cores from the buildbot. */
  val etag: String? = null,
  /** The build's `Last-Modified`, for cores from the buildbot. */
  val lastModified: String? = null,
)

/** A core's source has no build for this ABI, refused the download, or sent a broken one. */
class CoreDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The libretro cores installed for the ABI [abi], such as `arm64-v8a`, kept in [dir]/[abi] along
 * with a record of the build each came from. The session loads a core from [fileOf].
 *
 * Installing downloads the latest build from the core's [CoreSource] and replaces the installed
 * core only once the download is complete and unzipped, so a failed install or update leaves the
 * previous core in place.
 */
class InstalledCores(dir: File, private val abi: String, private val http: HttpClient) {
  private val abiDir = File(dir, abi)
  private val records =
    JsonFile(
      File(abiDir, "installed.json"),
      MapSerializer(String.serializer(), InstalledCore.serializer()),
    )
  private val mutex = Mutex()

  /** The file [core] is installed at, which exists only while it's installed. */
  fun fileOf(core: LibretroCore) = File(abiDir, "${core.library}.so")

  /** The installed build of [core], or null when it isn't installed. */
  suspend fun get(core: LibretroCore): InstalledCore? =
    withContext(Dispatchers.IO) { records.read()?.get(core.library) }

  /**
   * Downloads the latest build of [core] and installs it, replacing any installed build.
   *
   * @throws CoreDownloadException when the source has no build for [abi] or sends a broken one.
   * @throws IOException when the download fails.
   */
  suspend fun install(
    core: LibretroCore,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> },
  ): InstalledCore =
    withContext(Dispatchers.IO) {
      abiDir.mkdirs()
      val build = latestBuild(core)
      val zip = File.createTempFile(".${core.library}-", ".zip", abiDir)
      val library = File.createTempFile(".${core.library}-", ".so", abiDir)
      try {
        val headers = download(build.url, zip, onProgress)
        extractLibrary(zip, library)
        val installed =
          InstalledCore(
            core.library,
            release = build.release,
            etag = headers[HttpHeaders.ETag],
            lastModified = headers[HttpHeaders.LastModified],
          )
        mutex.withLock {
          Files.move(library.toPath(), fileOf(core).toPath(), ATOMIC_MOVE)
          records.write(records.read().orEmpty() + (core.library to installed))
        }
        installed
      } finally {
        zip.delete()
        library.delete()
      }
    }

  /**
   * Whether [core]'s source has a newer build than the installed one. A core that isn't installed
   * has one.
   */
  suspend fun hasUpdate(core: LibretroCore): Boolean {
    val installed = get(core) ?: return true
    return when (val source = core.source) {
      CoreSource.Buildbot -> {
        if (installed.etag == null && installed.lastModified == null) return true
        val response =
          http.head(buildbotUrl(core)) {
            installed.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            installed.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
          }
        when {
          response.status == HttpStatusCode.NotModified -> false
          response.status.isSuccess() -> true
          else -> throw serverError(buildbotUrl(core), response.status)
        }
      }
      is CoreSource.GitHubRelease -> latestRelease(source).tag != installed.release
    }
  }

  /**
   * Installs [core] if it isn't installed, or updates it when its source has a newer build, and
   * returns its file. An installed core stays as it is when the check or the update fails, as when
   * the device is offline. [onProgress] hears about a download, if there is one.
   *
   * @throws IOException when [core] isn't installed and can't be downloaded.
   */
  suspend fun ensure(
    core: LibretroCore,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> },
  ): File {
    if (get(core) == null) {
      install(core, onProgress)
    } else {
      try {
        if (hasUpdate(core)) install(core, onProgress)
      } catch (e: IOException) {
        log.w(e) { "Keeping the installed ${core.library}: its update failed" }
      }
    }
    return fileOf(core)
  }

  private class Build(val url: String, val release: String?)

  private suspend fun latestBuild(core: LibretroCore): Build =
    when (val source = core.source) {
      CoreSource.Buildbot -> Build(buildbotUrl(core), release = null)
      is CoreSource.GitHubRelease -> {
        val release = latestRelease(source)
        val asset = source.assetFor(abi, release.assets.map { it.name })
        if (asset == null) {
          log.w { "${source.repository} ${release.tag} has no build for $abi" }
          throw CoreDownloadException("No build for $abi")
        }
        Build(release.assets.first { it.name == asset }.url, release.tag)
      }
    }

  private fun buildbotUrl(core: LibretroCore) =
    "$BUILDBOT/$abi/${core.androidLibrary}_android.so.zip"

  private suspend fun latestRelease(source: CoreSource.GitHubRelease): GitHubReleaseJson {
    val url = "$GITHUB_API/repos/${source.repository}/releases/latest"
    val response = http.get(url) { header(HttpHeaders.Accept, "application/vnd.github+json") }
    if (!response.status.isSuccess()) throw serverError(url, response.status)
    return response.decode { CoreDownloadException("Unreadable release", it) }
  }

  /** Downloads [url] into [target], and returns the response's headers once it's all written. */
  private suspend fun download(
    url: String,
    target: File,
    onProgress: (Long, Long?) -> Unit,
  ): Headers =
    http.prepareGet(url).execute { response ->
      if (!response.status.isSuccess()) throw serverError(url, response.status)
      val total = response.contentLength()
      onProgress(0, total)
      val downloaded = response.bodyAsChannel().writeTo(target) { onProgress(it, total) }
      if (total != null && downloaded != total) {
        log.w { "$url: cut short at $downloaded of $total bytes" }
        throw CoreDownloadException("Core download cut short")
      }
      response.headers
    }

  @Serializable
  private class GitHubReleaseJson(
    @SerialName("tag_name") val tag: String,
    val assets: List<Asset>,
  ) {
    @Serializable class Asset(val name: String, @SerialName("browser_download_url") val url: String)
  }

  private fun serverError(url: String, status: HttpStatusCode): CoreDownloadException {
    log.w { "$url: $status" }
    return CoreDownloadException("Server error ${status.value}")
  }

  private companion object {
    val log = Logger.withTag("InstalledCores")

    const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    const val GITHUB_API = "https://api.github.com"
  }
}
