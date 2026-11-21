package app.viewfinder.ui.learn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.viewfinder.learn.DIAL_ORDER
import app.viewfinder.learn.Mode
import app.viewfinder.learn.Setting
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The mode dial, big, to turn with a finger: it clicks from mode to mode like the real one, and underneath, who
 * decides each of the three settings in that mode, you or the camera.
 */
@Composable
fun ModeDialPlayground(modifier: Modifier = Modifier) {
    val motion = Motion.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    var mode by rememberSaveable { mutableStateOf(Mode.Auto) }
    // The index mark is at the top; turning the dial brings a mode under it.
    val rotation = remember { Animatable(dialRotationFor(Mode.Auto, -90f)) }

    LaunchedEffect(Unit) {
        snapshotFlow { modeAt(rotation.value) }.collect { m ->
            if (m != mode) {
                mode = m
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .semantics {
                    contentDescription = "Mode dial. Drag around to turn it."
                    stateDescription = mode.title
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        var last = angle(down.position - c)
                        drag(down.id) { change ->
                            val a = angle(change.position - c)
                            var d = a - last
                            if (d > 180f) d -= 360f
                            if (d < -180f) d += 360f
                            last = a
                            scope.launch { rotation.snapTo(rotation.value + d) }
                            change.consume()
                        }
                        // Settle on the nearest click.
                        val target = dialRotationFor(modeAt(rotation.value), -90f)
                        scope.launch { rotation.animateTo(nearest(rotation.value, target), motion.bounce()) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val r = size.minDimension * 0.4f
                val c = center
                // The camera's top plate around the dial.
                drawCircle(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.12f), Color.Transparent), c, size.minDimension * 0.5f), size.minDimension * 0.5f, c)
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF262B28), Color(0xFF0C0E0D))), r * 1.16f, c)
                drawCircle(Color.White.copy(alpha = 0.10f), r * 1.16f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                // The index mark.
                val tip = Offset(c.x, c.y - r * 1.1f)
                drawPath(Path().apply { moveTo(tip.x, tip.y + r * 0.08f); lineTo(tip.x - r * 0.045f, tip.y - r * 0.01f); lineTo(tip.x + r * 0.045f, tip.y - r * 0.01f); close() }, Palette.Text)
                drawModeDial(c, r, rotation.value, measurer, 1f, mode)
            }
        }

        AnimatedContent(mode, transitionSpec = { motion.swap() }, label = "mode-card") { m ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleLarge)
                Text(m.what, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, minLines = 3)
            }
        }
        WhoDecides(mode)
    }
}

/** Three pills, one per setting, each saying who sets it in this mode. */
@Composable
private fun WhoDecides(mode: Mode) {
    val motion = Motion.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Setting.entries.forEach { s ->
            val you = s in mode.youSet
            val fill by animateColorAsState(if (you) Palette.AccentSoft else Palette.Text.copy(alpha = 0.06f), Motion.current.enter(), label = "who")
            val ink by animateColorAsState(if (you) Palette.Accent else Palette.TextSecondary, Motion.current.enter(), label = "who-ink")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(fill)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.title, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(4.dp))
                AnimatedContent(you, transitionSpec = { motion.morph() }, label = "who-text") { y ->
                    Text(if (y) "You" else "Camera", style = MaterialTheme.typography.titleSmall, color = ink)
                }
            }
        }
    }
}

/** Degrees clockwise from the top for a point relative to the centre. */
private fun angle(p: Offset): Float = (Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble())).toFloat() + 450f) % 360f

/** The mode under the top index mark at a dial rotation. */
private fun modeAt(rotation: Float): Mode {
    val steps = ((-rotation) / 45f).roundToInt()
    return DIAL_ORDER[((steps % 8) + 8) % 8]
}

private fun nearest(current: Float, target: Float): Float {
    var t = target
    while (t - current > 180f) t -= 360f
    while (current - t > 180f) t += 360f
    return t
}
