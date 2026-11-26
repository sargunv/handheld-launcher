package dev.sargunv.handheld

import android.content.Context
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.ui.Settings
import kotlinx.io.files.Path

/** The app's settings, in its private files directory. */
internal fun settingsFile(context: Context) =
  SettingsFile(
    Path(context.filesDir.resolve("settings.json").path),
    Settings.serializer(),
    Settings(),
  )
