package dev.sargunv.handheld

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/** Whether this is the system's HOME. */
internal fun Intent.isHome(): Boolean =
  action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true

/** Whether this app is the device's home app, the one HOME opens. */
internal fun Context.isDefaultHome(): Boolean =
  getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

/**
 * The intent that brings the launcher to the front, in a new task when it isn't running. While the
 * app is the home app, the launcher lives in the system's home task, which only HOME's intent
 * reaches: an explicit one would start a second launcher in a task of its own.
 */
internal fun Context.launcherIntent(): Intent =
  if (isDefaultHome()) {
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
    } else {
      Intent(this, MainActivity::class.java)
    }
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Marks an intent that brings the launcher to the front only to give it the controller back, which
 * HOME's intent carries without being a press of HOME.
 */
internal const val EXTRA_TAKE_FOCUS = "dev.sargunv.handheld.TAKE_FOCUS"
