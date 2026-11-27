package dev.sargunv.handheld.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.Download
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.downloads.DownloadProgress
import dev.sargunv.handheld.data.downloads.DownloadState
import dev.sargunv.handheld.data.downloads.StorageSpace
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusScope
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.input.onAction
import dev.sargunv.handheld.ui.labelled
import dev.sargunv.handheld.ui.places.MenuButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Downloads: the queue, in the order it downloads. Confirm opens a game with [onOpen], and the menu
 * cancels or retries its download.
 */
@Composable
internal fun DownloadsPlace(downloads: DownloadManager, onOpen: (Download) -> Unit) {
  val queue by downloads.queue.collectAsState()
  val progress by downloads.progress.collectAsState()
  var focusedId by remember { mutableStateOf<Long?>(null) }
  var menuFor by remember { mutableStateOf<Long?>(null) }
  val focused = queue.find { it.romId == focusedId }
  val scope = rememberCoroutineScope()

  FocusScope(
    Modifier.fillMaxSize().onAction { action ->
      if (action == Action.Menu && focused != null) {
        menuFor = focused.romId
        true
      } else {
        false
      }
    },
  ) {
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        if (queue.isEmpty()) {
          Text(
            "Queue empty",
            color = Placeholder.mutedText,
            modifier = Modifier.align(Alignment.Center),
          )
        } else {
          FocusLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(queue, key = { it.romId }) { download ->
              DownloadRow(
                download,
                download.progressIn(progress),
                Modifier.onFocusChanged { if (it.isFocused) focusedId = download.romId },
                onOpen = { onOpen(download) },
              )
            }
          }
        }
      }
      HintBar(
        buildList {
          if (focused != null) add(Hint(Action.Confirm, "Open"))
          add(Hint(Action.Back, "Home"))
          if (focused != null) add(Hint(Action.Menu, "Menu"))
        },
      )
    }
    val menuDownload = queue.find { it.romId == menuFor }
    LaunchedEffect(menuDownload == null) { if (menuDownload == null) menuFor = null }
    if (menuDownload != null) {
      val close = { menuFor = null }
      Dialog(close) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            menuDownload.title,
            color = Placeholder.text,
            modifier = Modifier.semantics { heading() },
          )
          if (menuDownload.state == DownloadState.Failed) {
            MenuButton("menu:retry", "Retry") {
              scope.launch { downloads.retry(menuDownload.romId) }
              close()
            }
          }
          MenuButton("menu:cancel", "Cancel download") {
            scope.launch { downloads.cancel(menuDownload.romId) }
            close()
          }
        }
      }
    }
  }
}

@Composable
private fun DownloadRow(
  download: Download,
  progress: DownloadProgress,
  modifier: Modifier,
  onOpen: () -> Unit,
) {
  val key = "download:${download.romId}"
  Column(
    modifier
      .focusItem(key, onConfirm = onOpen)
      .fillMaxWidth()
      .background(Placeholder.tile)
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(download.title, color = Placeholder.text)
    Text(statusText(download, progress), color = Placeholder.mutedText, fontSize = 14.sp)
    if (download.state == DownloadState.Downloading && progress.totalBytes > 0) {
      Box(Modifier.fillMaxWidth().height(4.dp).background(Placeholder.dialog)) {
        val fraction = (progress.bytesDownloaded.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Placeholder.text))
      }
    }
  }
}

private fun statusText(download: Download, progress: DownloadProgress): String =
  when (download.state) {
    DownloadState.Queued -> "Queued · ${formatSize(download.sizeBytes)}"
    DownloadState.Downloading -> {
      val (bytes, total) = progress
      val percent = if (total > 0) bytes * 100 / total else 0
      "${formatSize(bytes)} of ${formatSize(total)} · $percent%"
    }
    DownloadState.Failed -> downloadFailedLabel(download.error)
  }

internal fun downloadFailedLabel(error: String?): String = labelled("Download failed", error)

/**
 * The room each storage target has left, read again every few seconds while [active], since
 * downloads and other apps change it.
 */
@Composable
internal fun rememberStorageSpaces(
  downloads: DownloadManager,
  active: Boolean,
): State<List<StorageSpace>> =
  produceState(emptyList(), downloads, active) {
    while (active) {
      value = downloads.storageSpace()
      delay(5.seconds)
    }
  }

/** The lower screen on Downloads: the room each storage target has left. */
@Composable
internal fun StorageSummary(downloads: DownloadManager) {
  val spaces by rememberStorageSpaces(downloads, active = true)
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      "Storage",
      color = Placeholder.text,
      fontSize = 24.sp,
      modifier = Modifier.semantics { heading() },
    )
    for (space in spaces) {
      Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(space.target.label, color = Placeholder.text)
        Text(
          "${formatSize(space.freeBytes)} free · ${space.target.root}",
          color = Placeholder.mutedText,
        )
      }
    }
  }
}
