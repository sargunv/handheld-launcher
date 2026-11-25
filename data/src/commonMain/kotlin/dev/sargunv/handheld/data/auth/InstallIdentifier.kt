package dev.sargunv.handheld.data.auth

import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * A random id for this install, made the first time it's asked for and kept in [path], for
 * [DeviceDescription.identifier]. It identifies nothing about the hardware.
 */
fun installIdentifier(path: Path): String {
  if (SystemFileSystem.exists(path)) {
    return SystemFileSystem.source(path).buffered().use { it.readString() }
  }
  val id = Uuid.random().toString()
  path.parent?.let { SystemFileSystem.createDirectories(it) }
  SystemFileSystem.sink(path).buffered().use { it.writeString(id) }
  return id
}
