package dev.sargunv.handheld

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import dev.sargunv.handheld.ui.places.OpenRequest

/** Whether an intent with [action] and [categories] is the system's HOME. */
internal fun isHomeIntent(action: String?, categories: Set<String>?): Boolean =
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

/** What the launcher does with an intent that reaches it while it runs. */
internal sealed interface IntentResponse {
  /** Shows the screen a development tool asked for. */
  data class Open(val request: OpenRequest) : IntentResponse

  /** Takes HOME as a press in the app: it closes the dialogs, or starts over on Home. */
  data object PressHome : IntentResponse

  /** Only comes to the front, as it was. */
  data object Show : IntentResponse
}

/**
 * What the launcher does with a new intent: [home] when it's the system's HOME, [open] when a
 * development tool named a screen, [wasInFront] when the launcher had the focus as it came, and
 * [playing] while a game it started is under way.
 *
 * HOME is a press in the app only when the launcher was in front, the way launchers start over on
 * their first page. From another app it only brings the launcher back as it was, so HOME during a
 * standalone game returns to the Game screen, which syncs the save as it does after any play. A
 * game under way keeps its screen, since leaving it would stop the play and its sync.
 */
internal fun respondTo(
  home: Boolean,
  open: OpenRequest?,
  wasInFront: Boolean,
  playing: Boolean,
): IntentResponse =
  when {
    open != null -> IntentResponse.Open(open)
    home && wasInFront && !playing -> IntentResponse.PressHome
    else -> IntentResponse.Show
  }
