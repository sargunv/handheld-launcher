package dev.sargunv.handheld.ui.signin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.readText(): String? {
  val transferable = getClipEntry()?.nativeClipEntry as? Transferable ?: return null
  if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
  return transferable.getTransferData(DataFlavor.stringFlavor) as? String
}
