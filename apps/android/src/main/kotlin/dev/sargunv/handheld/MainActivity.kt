package dev.sargunv.handheld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.sargunv.handheld.data.EmptyLibrary
import dev.sargunv.handheld.ui.App

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val debugScreen = debugScreen(this)
    setContent { if (debugScreen != null) debugScreen() else App(EmptyLibrary.games()) }
  }
}
