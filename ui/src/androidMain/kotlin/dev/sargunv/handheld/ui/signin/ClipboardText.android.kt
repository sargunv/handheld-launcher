package dev.sargunv.handheld.ui.signin

import androidx.compose.ui.platform.Clipboard

internal actual suspend fun Clipboard.readText(): String? {
  val clip = getClipEntry()?.clipData ?: return null
  if (clip.itemCount == 0) return null
  return clip.getItemAt(0).text?.toString()
}
