package dev.sargunv.handheld

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The installed apps a game can open in: every app with a launcher icon, which the manifest's
 * `<queries>` lets the launcher see.
 */
internal class InstalledApps(private val context: Context) : AppCatalog {
  private fun launcherApps(): List<ApplicationInfo> =
    context.packageManager
      .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .map { it.activityInfo.applicationInfo }
      .distinctBy { it.packageName }

  /** The apps other than the launcher itself, for the player to pick. */
  override suspend fun apps(): List<InstalledApp> =
    withContext(Dispatchers.IO) {
      launcherApps()
        .filter { it.packageName != context.packageName }
        .map {
          InstalledApp(it.packageName, context.packageManager.getApplicationLabel(it).toString())
        }
    }

  /** The apps' package names. */
  fun packageNames(): Set<String> = launcherApps().mapTo(mutableSetOf()) { it.packageName }

  /** The version name of [packageName], for recipes that depend on the installed build. */
  fun versionName(packageName: String): String? = runCatching {
    context.packageManager.getPackageInfo(packageName, 0).versionName
  }
    .getOrNull()
}
