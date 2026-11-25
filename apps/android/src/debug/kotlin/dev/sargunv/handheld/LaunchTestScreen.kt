package dev.sargunv.handheld

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.domain.launch.LaunchBuild
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchInput
import dev.sargunv.handheld.domain.launch.LaunchResolution
import dev.sargunv.handheld.domain.launch.LaunchResolver
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.launch.LaunchDispatcher
import dev.sargunv.handheld.launch.LaunchOutcome

/**
 * Launches [rom], a game for [platform], the way the Game screen will: the resolver picks the app,
 * and the dispatcher starts it. [app], when given, is the game's own override, standing in for a
 * rule the user would set.
 */
@Composable
internal fun LaunchTestScreen(activity: Activity, rom: String?, platform: String?, app: String?) {
  var status by remember { mutableStateOf("Launching") }
  LaunchedEffect(rom, platform, app) {
    if (rom == null || platform == null) {
      status = "Pass the game with --es rom <path> --es platform <slug>"
      return@LaunchedEffect
    }
    val dispatcher = LaunchDispatcher(activity)
    val gameId = 0L
    val rules =
      LaunchRules(gameOverrides = app?.let { mapOf(gameId to LaunchChoice(it)) }.orEmpty())
    val subject = LaunchSubject(gameId, PlatformSlug(platform))
    status =
      when (
        val resolution = LaunchResolver.resolve(subject, rules, dispatcher.installedPackages())
      ) {
        is LaunchResolution.NoEmulatorInstalled ->
          "No emulator for $platform is installed. Try " +
            resolution.suggestions.joinToString { it.displayName }
        is LaunchResolution.Resolved -> {
          val target = resolution.target
          val input =
            LaunchInput(
              rom,
              subject.platform,
              installedVersionName = dispatcher.versionName(target.packageName),
            )
          when (val build = target.commandFor(input)) {
            is LaunchBuild.MissingCore -> "No libretro core for $platform"
            is LaunchBuild.Ready ->
              when (val outcome = dispatcher.launch(build.command)) {
                is LaunchOutcome.Started ->
                  "Started ${target.packageName} (${resolution.rule}) by ${outcome.method}"
                LaunchOutcome.NeedsAllFilesAccess -> {
                  activity.startActivity(dispatcher.allFilesAccessSettings())
                  "Grant all-files access, then launch again"
                }
                is LaunchOutcome.Failed -> outcome.reason
              }
          }
        }
      }
    Log.i("LaunchTest", status)
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    BasicText(
      status,
      Modifier.align(Alignment.Center).padding(16.dp),
      style = TextStyle(color = Color.White),
    )
  }
}
