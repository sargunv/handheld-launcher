package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.PerGameState
import dev.sargunv.handheld.domain.saves.BuiltInSaves
import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The built-in session's save folders, one for each game ([BuiltInSaves]). When a local game
 * becomes a ROM, its folder becomes the ROM's, so its saves sync from then on. When the ROM's
 * folder holds saves already, those stay the ROM's, and the local game's files go to the ROM's save
 * history in [history], the history of a [SaveSync], as a conflict's other copy does. Neither copy
 * is lost.
 */
class BuiltInSaveFolders(
  val saves: BuiltInSaves,
  private val history: File,
  private val clock: Clock = Clock.System,
) : PerGameState {
  override suspend fun adopt(adoptions: List<Adoption>) =
    withContext(Dispatchers.IO) {
      for (adoption in adoptions) {
        try {
          adopt(adoption)
        } catch (e: IOException) {
          // What's left stays in the local folder, which the next adoption tries again.
        }
      }
    }

  private fun adopt(adoption: Adoption) {
    val local = File(saves.folderOf(adoption.local))
    if (!local.isDirectory) return
    val own = File(saves.folderOf(adoption.rom))
    val files = filesIn(local)
    if (files.isNotEmpty() && (!own.exists() || own.isDirectory && filesIn(own).isEmpty())) {
      // An empty folder, such as one a launch canceled at the save choice left, holds no saves.
      own.deleteRecursively()
      own.parentFile?.mkdirs()
      // Both are in the app's files, so the move is one step, or nothing moves.
      if (!local.renameTo(own)) throw IOException("Couldn't move $local to $own")
    } else {
      val now = clock.now()
      for (file in files) {
        // The history is one folder, so a file in a subfolder is named for its path.
        val name = file.relativeTo(local).invariantSeparatorsPath.replace('/', '_')
        keepInHistory(
          history,
          builtInHistoryKey(adoption.rom.romId),
          SaveCopy.Device,
          name,
          file.readBytes(),
          now,
        )
        if (!file.delete()) throw IOException("Couldn't delete $file")
      }
      local.deleteRecursively()
    }
    pruneEmpty(local.parentFile)
  }

  /** Deletes [folder] and the folders above it, up to the local games' own, while they're empty. */
  private fun pruneEmpty(folder: File?) {
    val root = File(saves.localRoot).absoluteFile
    var current = folder?.absoluteFile
    while (current != null && current != root && current.startsWith(root)) {
      if (current.list()?.isEmpty() != true || !current.delete()) return
      current = current.parentFile
    }
  }

  private fun filesIn(folder: File): List<File> = folder.walkTopDown().filter { it.isFile }.toList()
}
