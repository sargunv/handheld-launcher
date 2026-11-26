package dev.sargunv.handheld

import android.app.ActivityOptions
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.sargunv.handheld.ui.dualscreen.EmptySecondaryScreen

/**
 * The home screen of the second display while the app is the home app. Android starts one there for
 * the home app, and without one of ours it starts another launcher's, whose apps the controller or
 * a tap on the lower screen would reach.
 *
 * It shows nothing of its own. The launcher draws its lower screen over it in a presentation, and
 * the play companion shows over it during a game in another app. Its window takes neither focus nor
 * touch, so nothing on the lower screen takes the controller from the upper one.
 */
class SecondaryHomeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    )
    setContent { EmptySecondaryScreen() }
  }

  override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
    super.onTopResumedActivityChanged(isTopResumedActivity)
    // Android sends the controller to the display it last started an activity on, which is this
    // one once it starts the display's home, and this window can't take it. The launcher takes it
    // back when it's what the first display shows; anything else there stays as it is.
    if (isTopResumedActivity && isLauncherResumed()) {
      startActivity(
        launcherIntent().putExtra(EXTRA_TAKE_FOCUS, true),
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
      )
    }
  }
}
