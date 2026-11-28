package dev.sargunv.handheld.ui.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import com.composeunstyled.Text
import dev.sargunv.handheld.ui.Placeholder
import java.io.File
import okio.Path.Companion.toPath

/**
 * A game's box art from [url]. While it loads, or when there's none, a neutral tile shows the
 * game's [title] instead.
 *
 * @param description what accessibility services say for the cover, whether it shows the art or the
 *   title; null for a cover beside its game's title, which needs no label of its own.
 */
@Composable
internal fun Cover(
  url: String?,
  title: String,
  modifier: Modifier = Modifier,
  description: String? = title,
) {
  var loaded by remember(url) { mutableStateOf(false) }
  Box(
    modifier.background(Placeholder.tile).clearAndSetSemantics {
      description?.let { contentDescription = it }
    },
  ) {
    if (!loaded) {
      Text(
        title,
        color = Placeholder.text,
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
      )
    }
    if (url != null) {
      AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.matchParentSize(),
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true },
      )
    }
  }
}

/**
 * The image loader for covers. It keeps the covers it fetched in [cacheDir], so they show offline
 * and after a restart. The server's covers need no sign-in, so requests carry no token, which also
 * keeps it from reaching the metadata sources' image hosts.
 */
fun coverImageLoader(context: PlatformContext, cacheDir: File): ImageLoader =
  ImageLoader.Builder(context)
    .diskCache {
      DiskCache.Builder().directory(cacheDir.path.toPath()).maxSizeBytes(CACHE_SIZE).build()
    }
    .build()

/** Enough for the covers of a large library at the sizes the server keeps. */
private const val CACHE_SIZE = 512L * 1024 * 1024
