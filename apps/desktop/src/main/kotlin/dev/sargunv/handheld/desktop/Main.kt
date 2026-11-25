package dev.sargunv.handheld.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.data.EmptyLibrary
import dev.sargunv.handheld.ui.App

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Handheld",
    state = rememberWindowState(width = 1280.dp, height = 720.dp),
  ) {
    App(EmptyLibrary.games())
  }
}
