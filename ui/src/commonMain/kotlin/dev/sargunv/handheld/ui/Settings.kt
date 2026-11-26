package dev.sargunv.handheld.ui

import dev.sargunv.handheld.ui.achievements.AchievementSettings
import dev.sargunv.handheld.ui.game.LaunchSettings
import dev.sargunv.handheld.ui.home.HomeSettings
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.library.LibrarySettings
import kotlinx.serialization.Serializable

/**
 * The settings the app keeps between launches. Each is changed where it applies: a game's in its
 * menu, and the device-wide ones on the Settings page.
 *
 * Add a setting as a property with a default. The default is also its value on a device that stored
 * its settings before the property existed.
 */
@Serializable
data class Settings(
  val input: InputSettings = InputSettings(),
  val home: HomeSettings = HomeSettings(),
  val library: LibrarySettings = LibrarySettings(),
  /** The apps the user picked to open games in. */
  val launch: LaunchSettings = LaunchSettings(),
  /**
   * Whether the launcher checks if a standalone emulator is still running, and offers to close it,
   * on devices that let it.
   */
  val closeEmulators: Boolean = false,
  val achievements: AchievementSettings = AchievementSettings(),
)
