package dev.sargunv.handheld.data.auth

import java.io.File
import kotlin.uuid.Uuid

/**
 * A random id for this install, made the first time it's asked for and kept in [file], for
 * [DeviceDescription.identifier]. It identifies nothing about the hardware.
 */
fun installIdentifier(file: File): String {
  if (file.exists()) return file.readText()
  val id = Uuid.random().toString()
  file.absoluteFile.parentFile.mkdirs()
  file.writeText(id)
  return id
}
