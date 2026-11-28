package dev.sargunv.handheld.ui

import dev.sargunv.handheld.data.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.GameLauncher
import dev.sargunv.handheld.ui.game.RomHeaders

/**
 * What the platform the app runs on gives it besides its data.
 *
 * @param device this device, as it introduces itself to the server when it signs in.
 * @param launcher plays games, on platforms that can.
 * @param apps the apps on the device that the player can pick to open games in.
 * @param romHeaders reads what a game's file says about itself, for the Game screen.
 * @param retroAchievements the device's RetroAchievements account, on platforms whose games can
 *   earn achievements.
 * @param appInfo what About says about this build: its version and the components it's built from.
 */
class AppPlatform(
  val device: DeviceDescription,
  val launcher: GameLauncher = GameLauncher.Unsupported,
  val apps: AppCatalog = AppCatalog.None,
  val romHeaders: RomHeaders = RomHeaders.None,
  val retroAchievements: RetroAchievementsSignIn? = null,
  val appInfo: AppInfo = AppInfo.Unknown,
)
