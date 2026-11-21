package app.viewfinder.ui.learn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.tabular
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A camera-style setting scale: a ruler of values that slides under a fixed centre mark, clicking from stop to stop
 * like the command dial. Drag or fling it; it always settles on a value.
 */
@Composable
fun ValueScale(
    label: String,
    values: List<String>,
    index: Int,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spacingDp: Float = 64f,
) {
    val motion = Motion.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacing = with(density) { spacingDp.dp.toPx() }
    val position = remember { Animatable(index.toFloat()) }
    val scope = rememberCoroutineScope()
    var reported by remember { mutableIntStateOf(index) }
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.titleMedium.tabular()
    val labelStyle = MaterialTheme.typography.labelMedium

    // Outside changes (a reset) move the ruler too.
    LaunchedEffect(index) { if (index != reported) { reported = index; position.animateTo(index.toFloat(), motion.move()) } }
    // Every stop passed clicks, the way a detented dial does.
    LaunchedEffect(Unit) {
        snapshotFlow { position.value.roundToInt().coerceIn(0, values.lastIndex) }.collect { i ->
            if (i != reported) {
                reported = i
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                onIndex(i)
            }
        }
    }

    Column(modifier) {
        Text(label, style = labelStyle, color = Palette.TextSecondary)
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .semantics {
                    contentDescription = "$label ${values[index]}"
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..values.lastIndex.toFloat(), values.size - 2)
                    setProgress { v -> onIndex(v.roundToInt().coerceIn(0, values.lastIndex)); true }
                }
                .draggable(
                    rememberDraggableState { d -> scope.launch { position.snapTo((position.value - d / spacing).coerceIn(-0.4f, values.lastIndex + 0.4f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        // Fling, then settle on the nearest stop.
                        val target = exponentialDecay<Float>(frictionMultiplier = 2.5f).calculateTargetValue(position.value, -velocity / spacing)
                        position.animateTo(target.roundToInt().coerceIn(0, values.lastIndex).toFloat(), motion.bounce())
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawRoundRect(Palette.Text.copy(alpha = 0.06f), cornerRadius = CornerRadius(16.dp.toPx()))
                val cx = size.width / 2
                val p = position.value
                for (i in values.indices) {
                    val x = cx + (i - p) * spacing
                    if (x < -spacing || x > size.width + spacing) continue
                    val near = (1f - abs(i - p)).coerceIn(0f, 1f)
                    // Minor ticks between stops.
                    for (k in 1..2) {
                        val mx = x + k * spacing / 3
                        if (i < values.lastIndex) drawLine(Palette.Muted.copy(alpha = 0.5f), Offset(mx, 10.dp.toPx()), Offset(mx, 18.dp.toPx()), 1.dp.toPx())
                    }
                    drawLine(Palette.Text.copy(alpha = 0.35f + 0.65f * near), Offset(x, 8.dp.toPx()), Offset(x, 22.dp.toPx()), 1.5.dp.toPx())
                    val layout = measurer.measure(values[i], valueStyle.copy(color = lerp(Palette.TextSecondary, Palette.Text, near)))
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, size.height - layout.size.height - 10.dp.toPx()))
                }
                // The centre mark.
                drawRoundRect(Palette.Accent, Offset(cx - 1.5.dp.toPx(), 4.dp.toPx()), Size(3.dp.toPx(), 22.dp.toPx()), CornerRadius(2.dp.toPx()))
                // Values fade into the edges.
                val fade = size.width * 0.18f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade), blendMode = BlendMode.DstIn)
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width), blendMode = BlendMode.DstIn)
            }
        }
    }
}
