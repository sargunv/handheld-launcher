package dev.sargunv.handheld.achievements

import android.content.Context
import dev.sargunv.handheld.data.achievements.KeystoreRetroAchievementsStore
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.session.rcheevosUserAgentClause
import dev.sargunv.handheld.ui.achievements.RetroAchievementsSignIn
import java.io.File

/** Where the RetroAchievements account lives: encrypted with a key in the Android Keystore. */
internal fun retroAchievementsStore(context: Context) =
  KeystoreRetroAchievementsStore(File(context.applicationContext.filesDir, "retroachievements"))

/**
 * The User-Agent RetroAchievements asks clients for: the app's name and version, then the version
 * of rcheevos the session is built with.
 */
internal fun retroAchievementsUserAgent(context: Context): String {
  val version =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
  return RetroAchievementsClient.userAgent(version, rcheevosUserAgentClause())
}

/** A client for RetroAchievements, which names the app as RetroAchievements asks. */
internal fun retroAchievementsClient(context: Context) =
  RetroAchievementsClient(retroAchievementsUserAgent(context))

/** The device's RetroAchievements sign-in, for the launcher's quick menu. */
internal fun retroAchievementsSignIn(context: Context) =
  RetroAchievementsSignIn(retroAchievementsStore(context)) { retroAchievementsClient(context) }
