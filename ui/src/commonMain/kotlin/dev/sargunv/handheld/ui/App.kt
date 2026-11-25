package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.composeunstyled.Text
import dev.sargunv.handheld.domain.Game

/** The app's root, shared by every platform. */
@Composable
fun App(games: List<Game>) {
  Box(Modifier.fillMaxSize().background(Color(0xFF2B2A28)), contentAlignment = Alignment.Center) {
    Text(
      text = if (games.isEmpty()) "No games yet" else "${games.size} games",
      color = Color(0xFFE8E4DC),
    )
  }
}
