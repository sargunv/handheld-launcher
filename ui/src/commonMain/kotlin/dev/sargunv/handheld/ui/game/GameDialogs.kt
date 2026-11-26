package dev.sargunv.handheld.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.data.downloads.formatSize
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.places.MenuButton

/** The Game screen's menu, with what [game]'s [status] allows. */
@Composable
internal fun GameMenu(
  game: LibraryGame,
  status: RomStatus?,
  onDismissRequest: () -> Unit,
  onCancelDownload: () -> Unit,
  onRemove: () -> Unit,
  onUpload: () -> Unit,
  onOpenWith: () -> Unit,
) {
  Dialog(onDismissRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(game.title, color = Placeholder.text, modifier = Modifier.semantics { heading() })
      // Download and Retry are the primary action, so the menu doesn't repeat them.
      when (status) {
        RomStatus.Queued,
        is RomStatus.Downloading,
        is RomStatus.Failed ->
          if (game is Rom)
            MenuButton("menu:cancel", "Cancel download", onConfirm = onCancelDownload)
        is RomStatus.OnDevice ->
          MenuButton("menu:remove", "Remove from device", onConfirm = onRemove)
        RomStatus.NotOnDevice,
        null -> Unit
      }
      // A disc set's other files would stay behind, since the upload sends one file.
      if (game is LocalGame && game.otherFiles.isEmpty()) {
        MenuButton("menu:upload", "Upload to RomM", onConfirm = onUpload)
      }
      MenuButton("menu:open-with", "Open with", onConfirm = onOpenWith)
    }
  }
}

/** Asks before removing [game] from the device. Cancel is focused first. */
@Composable
internal fun RemoveDialog(game: LibraryGame, onDismissRequest: () -> Unit, onRemove: () -> Unit) {
  Dialog(onDismissRequest, initialFocus = "remove:cancel") {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Remove ${game.title} from this device?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        when (game) {
          is Rom -> "Its saves stay, here and on RomM, and you can download it again."
          is LocalGame ->
            "It isn't on your server, so you can't download it again. Its saves stay on " +
              "this device."
        },
        color = Placeholder.mutedText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("remove:cancel", "Cancel", onConfirm = onDismissRequest)
        MenuButton("remove:confirm", "Remove", onConfirm = onRemove)
      }
    }
  }
}

/** Asks before uploading [game] to RomM. Cancel is focused first. */
@Composable
internal fun UploadDialog(game: LocalGame, onDismissRequest: () -> Unit, onUpload: () -> Unit) {
  Dialog(onDismissRequest, initialFocus = "upload:cancel") {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Upload ${game.title} to RomM?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        "It goes into its platform's folder on your server as ${game.fileName} " +
          "(${formatSize(game.sizeBytes)}). Once RomM has it in your library, its saves " +
          "sync like any game's.",
        color = Placeholder.mutedText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("upload:cancel", "Cancel", onConfirm = onDismissRequest)
        MenuButton("upload:confirm", "Upload", onConfirm = onUpload)
      }
    }
  }
}

/** Asks before stopping the upload of [title]. Keeping on is focused first. */
@Composable
internal fun StopUploadDialog(title: String, onKeep: () -> Unit, onStop: () -> Unit) {
  Dialog(onKeep, initialFocus = "upload:keep") {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Stop uploading $title?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text("RomM drops what it received so far.", color = Placeholder.mutedText)
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("upload:keep", "Keep uploading", onConfirm = onKeep)
        MenuButton("upload:stop", "Stop upload", onConfirm = onStop)
      }
    }
  }
}

/**
 * Asks whether to leave a game while its launch is under way, which stops the launch. Staying is
 * focused first.
 */
@Composable
internal fun LeaveDialog(title: String, onStay: () -> Unit, onLeave: () -> Unit) {
  Dialog(onStay, initialFocus = "leave:stay") {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        "Leave $title?",
        color = Placeholder.text,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        "A game that hasn't started yet won't start. One already playing in another app keeps " +
          "running, but its save won't sync with RomM when you quit it.",
        color = Placeholder.mutedText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuButton("leave:stay", "Stay", onConfirm = onStay)
        MenuButton("leave:confirm", "Leave", onConfirm = onLeave)
      }
    }
  }
}
