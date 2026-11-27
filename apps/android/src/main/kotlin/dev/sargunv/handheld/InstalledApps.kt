package dev.sargunv.handheld

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import dev.sargunv.handheld.ui.game.AppCatalog
import dev.sargunv.handheld.ui.game.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The apps with a launcher icon, which the manifest's `<queries>` lets the launcher see. */
internal fun launcherApps(context: Context): List<ApplicationInfo> =
  context.packageManager
    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    .map { it.activityInfo.applicationInfo }
    .distinctBy { it.packageName }

/** The [launcherApps] other than the launcher itself. */
internal class InstalledApps(private val context: Context) : AppCatalog {
  override suspend fun apps(): List<InstalledApp> =
    withContext(Dispatchers.IO) {
      launcherApps(context)
        .filter { it.packageName != context.packageName }
        .map {
          InstalledApp(it.packageName, context.packageManager.getApplicationLabel(it).toString())
        }
    }
}
