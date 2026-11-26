package dev.sargunv.handheld.data.downloads

import kotlinx.serialization.Serializable

/**
 * Which storage new downloads may go to, and in what order to try them. By default every storage is
 * on, in the order the device lists them: its own storage first, then any SD card.
 *
 * It only chooses where new games go. Games on storage that's turned off still count as on the
 * device, since finding them looks everywhere.
 */
@Serializable
data class StorageSettings(
  /**
   * The [StorageTarget.id]s in the order to fill them, including storage that isn't mounted now,
   * which keeps its place. Storage missing from it, such as a card inserted since, goes last.
   */
  val order: List<String> = emptyList(),
  /** The storage that new downloads skip. */
  val off: Set<String> = emptySet(),
) {
  /** The [mounted] storage, in the order to fill it. */
  fun arrange(mounted: List<StorageTarget>): List<StorageTarget> {
    val byId = mounted.associateBy { it.id }
    return all(mounted).mapNotNull { byId[it] }
  }

  /** The [mounted] storage that new downloads may go to, in the order to fill it. */
  fun enabled(mounted: List<StorageTarget>): List<StorageTarget> =
    arrange(mounted).filter { it.id !in off }

  fun isOn(target: StorageTarget) = target.id !in off

  fun turnOn(target: StorageTarget) = copy(off = off - target.id)

  /**
   * Turns [target] off, or returns null when it's the last of the [mounted] storage that's on, so
   * new downloads always have somewhere to go.
   */
  fun turnOff(target: StorageTarget, mounted: List<StorageTarget>): StorageSettings? {
    if (enabled(mounted).none { it.id != target.id }) return null
    return copy(off = off + target.id)
  }

  /** Moves [target] before the [mounted] storage above it. Does nothing to the first one. */
  fun moveUp(target: StorageTarget, mounted: List<StorageTarget>) = swap(target, mounted, -1)

  /** Moves [target] after the [mounted] storage below it. Does nothing to the last one. */
  fun moveDown(target: StorageTarget, mounted: List<StorageTarget>) = swap(target, mounted, 1)

  /** Every storage in order, known first, then any newly [mounted]. */
  private fun all(mounted: List<StorageTarget>) = (order + mounted.map { it.id }).distinct()

  private fun swap(
    target: StorageTarget,
    mounted: List<StorageTarget>,
    step: Int,
  ): StorageSettings {
    val shown = arrange(mounted)
    val index = shown.indexOfFirst { it.id == target.id }
    if (index < 0) return this
    val neighbor = shown.getOrNull(index + step)?.id ?: return this
    val reordered =
      all(mounted).map {
        when (it) {
          target.id -> neighbor
          neighbor -> target.id
          else -> it
        }
      }
    return copy(order = reordered)
  }
}

/**
 * The first of the [mounted] storage that [settings] lets downloads go to and that has room for
 * [bytes], by how much each has [free].
 *
 * @throws DownloadException when none of them has room, or none is mounted and on.
 */
internal fun chooseStorage(
  mounted: List<StorageTarget>,
  settings: StorageSettings,
  bytes: Long,
  free: (StorageTarget) -> Long,
): StorageTarget {
  if (mounted.isEmpty()) throw DownloadException("There's no storage to download to")
  val enabled = settings.enabled(mounted)
  if (enabled.isEmpty()) {
    throw DownloadException(
      "The storage that downloads go to isn't inserted. Insert it, or turn on other storage in " +
        "Settings.",
    )
  }
  val spaces = enabled.map { it to free(it) }
  spaces
    .firstOrNull { (_, room) -> room >= bytes }
    ?.let {
      return it.first
    }
  val (target, room) = spaces.maxBy { it.second }
  throw notEnoughSpace(target, bytes, room)
}
