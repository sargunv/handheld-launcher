package dev.sargunv.handheld.ui

import dev.sargunv.handheld.ui.input.InputSettings
import kotlinx.serialization.Serializable

/**
 * The settings the app keeps between launches. Each is changed on the screen it applies to, not on
 * a settings screen.
 *
 * Add a setting as a property with a default. The default is also its value on a device that stored
 * its settings before the property existed.
 */
@Serializable
data class Settings(
  val input: InputSettings = InputSettings(),
  /** Whether a game in the session pauses while its window lacks focus. */
  val pauseGameWhenUnfocused: Boolean = false,
)
