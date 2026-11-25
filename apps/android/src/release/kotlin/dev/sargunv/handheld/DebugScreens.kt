package dev.sargunv.handheld

import android.app.Activity
import androidx.compose.runtime.Composable

/** Release builds have no debug screens. */
internal fun debugScreen(activity: Activity): (@Composable () -> Unit)? = null
