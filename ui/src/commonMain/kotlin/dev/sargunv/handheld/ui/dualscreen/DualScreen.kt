package dev.sargunv.handheld.ui.dualscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import dev.sargunv.handheld.ui.Palette
import kotlin.math.roundToInt

/** How the two screens' content shares the hardware. */
enum class DualScreenLayout {
  /**
   * The secondary content has a display of its own, which the platform hosts, so only the primary
   * content shows here.
   */
  Separate,

  /** There's one screen: the secondary content folds into a side panel next to the primary. */
  Folded,

  /**
   * A development frame: both screens drawn in one window, stacked at the [ThorScreens]'
   * proportions and scaled to fit.
   */
  Frame,
}

/** A screen's size in pixels. */
@Immutable data class ScreenSize(val width: Int, val height: Int)

/** The AYN Thor's two screens: a 1920x1080 top screen over a 1240x1080 bottom one. */
object ThorScreens {
  val top = ScreenSize(1920, 1080)
  val bottom = ScreenSize(1240, 1080)
}

/**
 * Hosts the [primary] (top screen) and [secondary] (bottom screen) content in the given [layout].
 *
 * Controller focus lives in the primary content. The secondary content is for context and touch, so
 * it shouldn't hold focusable items.
 */
@Composable
fun DualScreen(
  layout: DualScreenLayout,
  modifier: Modifier = Modifier,
  primary: @Composable () -> Unit,
  secondary: @Composable () -> Unit,
) {
  when (layout) {
    DualScreenLayout.Separate -> Box(modifier.fillMaxSize()) { primary() }
    DualScreenLayout.Folded ->
      Row(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { primary() }
        Box(Modifier.fillMaxHeight().fillMaxWidth(FoldedPanelFraction)) { secondary() }
      }
    DualScreenLayout.Frame ->
      DualScreenFrame(modifier, ThorScreens.top, ThorScreens.bottom, primary, secondary)
  }
}

private const val FoldedPanelFraction = 0.3f

/**
 * Draws [top] over [bottom], centered and scaled together to fit. Each screen lays out at its pixel
 * size in the host's density, so a frame on a device lays out the way full-screen content would on
 * a screen of that size there.
 */
@Composable
private fun DualScreenFrame(
  modifier: Modifier,
  top: ScreenSize,
  bottom: ScreenSize,
  primary: @Composable () -> Unit,
  secondary: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxSize().background(FrameColor)) {
    val gap = top.height / 24
    val frameWidth = maxOf(top.width, bottom.width)
    val frameHeight = top.height + gap + bottom.height
    val scale =
      minOf(
        constraints.maxWidth.toFloat() / frameWidth,
        constraints.maxHeight.toFloat() / frameHeight,
      )
    Layout(
      content = {
        ScaledScreen(top, scale) { primary() }
        ScaledScreen(bottom, scale) { secondary() }
      },
    ) { measurables, constraints ->
      val placeables = measurables.map { it.measure(Constraints()) }
      layout(constraints.maxWidth, constraints.maxHeight) {
        val originX = (constraints.maxWidth - frameWidth * scale) / 2
        val originY = (constraints.maxHeight - frameHeight * scale) / 2
        val (topPlaceable, bottomPlaceable) = placeables
        topPlaceable.place(
          (originX + (frameWidth - top.width) * scale / 2).roundToInt(),
          originY.roundToInt(),
        )
        bottomPlaceable.place(
          (originX + (frameWidth - bottom.width) * scale / 2).roundToInt(),
          (originY + (top.height + gap) * scale).roundToInt(),
        )
      }
    }
  }
}

/** Lays [content] out at [size] and draws it scaled by [scale], taking up the scaled size. */
@Composable
private fun ScaledScreen(size: ScreenSize, scale: Float, content: @Composable () -> Unit) {
  Layout(
    content = {
      Box(
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
            clip = true
          }
          .background(ScreenColor),
      ) {
        content()
      }
    },
  ) { measurables, _ ->
    val placeable = measurables.single().measure(Constraints.fixed(size.width, size.height))
    val scaled = IntSize((size.width * scale).roundToInt(), (size.height * scale).roundToInt())
    layout(scaled.width, scaled.height) { placeable.place(0, 0) }
  }
}

private val FrameColor = Color(0xFF111111)
private val ScreenColor = Color.Black

/** The second screen with nothing of the app on it, in its usual background. */
@Composable
fun EmptySecondaryScreen(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().background(Palette.secondaryBackground))
}
