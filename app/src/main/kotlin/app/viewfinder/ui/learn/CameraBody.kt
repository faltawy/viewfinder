package app.viewfinder.ui.learn

import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.viewfinder.learn.Control
import app.viewfinder.learn.Mode
import app.viewfinder.ui.Motion
import app.viewfinder.ui.tabular
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The resting view: from behind and above the grip, at the anatomy figures' 30° elevation. */
private const val HERO_YAW = -34f
private const val HERO_PITCH = 30f

private val CardInk = Color(0xFF0F1211)
private val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.4.sp, color = Color(0xFF6E7671))
private val Readout = Label.copy(color = Color(0xFFE6EBE7), letterSpacing = 0.4.sp)

/**
 * Fig. 1, the D5600 on its turntable, drawn in the anatomy style: drag to turn it, tap any part. [highlighted]
 * controls breathe lit, [visited] ones rest; the [selected] one is turned to, glided in on, and acts out what it
 * does while the readout narrates.
 */
@Composable
fun CameraBody(
    highlighted: List<Control>,
    visited: Set<Control>,
    selected: Control?,
    onSelect: (Control?) -> Unit,
    modifier: Modifier = Modifier,
    mode: Mode = Mode.Auto,
) {
    val motion = Motion.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val rig = remember(mode) { buildD5600(mode) }
    val painter = remember(rig) { Painter(rig, Typeface.MONOSPACE, density) }
    val spots = remember(rig) { rig.spots.associateBy { it.control } }
    val first = highlighted.firstOrNull()?.let { spots[it] }
    val yaw = remember { Animatable(first?.yaw ?: HERO_YAW) }
    val pitch = remember { Animatable(first?.pitch ?: HERO_PITCH) }
    val zoom = remember { Animatable(1f) }
    val focusX = remember { Animatable(0.5f) }
    val focusY = remember { Animatable(0.5f) }
    val act = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }

    fun layout(w: Float, h: Float) = Triple(w / 2f, h * 0.5f, w / 250f)

    // Choosing a control: let go of any close-up, turn to show it, glide in, then let it act.
    LaunchedEffect(selected) {
        act.snapTo(0f)
        val spot = selected?.let { spots[it] }
        if (spot == null) {
            zoom.animateTo(1f, motion.move())
            return@LaunchedEffect
        }
        if (zoom.value > 1.01f) zoom.animateTo(1f, motion.move())
        launch { yaw.animateTo(nearest(yaw.value, spot.yaw), motion.move()) }
        pitch.animateTo(spot.pitch, motion.move())
        if (size != IntSize.Zero) {
            val (cx, cy, s) = layout(size.width.toFloat(), size.height.toFloat())
            painter.setView(cx, cy, s, yaw.value, pitch.value)
            val p = painter.project(spot.at)
            launch { focusX.animateTo((p.x / size.width).coerceIn(0.2f, 0.8f), motion.move()) }
            launch { focusY.animateTo((p.y / size.height).coerceIn(0.2f, 0.8f), motion.move()) }
        }
        zoom.animateTo(1.7f, motion.move())
        act.animateTo(1f, motion.demo())
    }

    // An idle tour: at rest the turntable sways slowly side to side; still with reduced motion or while touched.
    val sway = if (motion.reduced) 0f else {
        val t = rememberInfiniteTransition(label = "tour")
        val v by t.animateFloat(-1f, 1f, Motion.swayLoop(), label = "tour")
        v
    }
    val swayAmount = remember { Animatable(1f) }
    LaunchedEffect(selected, dragging) { swayAmount.animateTo(if (selected == null && !dragging) 1f else 0f, motion.blend()) }
    // Controls still to visit breathe between their usual line and lit.
    val breath = if (motion.reduced) 0.7f else {
        val t = rememberInfiniteTransition(label = "lit")
        val v by t.animateFloat(0.35f, 1f, Motion.pulseLoop(), label = "lit")
        v
    }

    val shownYaw = yaw.value + sway * 10f * swayAmount.value
    val angle = (((-shownYaw + HERO_YAW) % 360f + 360f) % 360f).roundToInt()
    val readout = buildString {
        append("${angle}°")
        when {
            selected != null -> append(" · ").append(stateOf(selected, act.value))
            highlighted.isNotEmpty() -> append(" · ${highlighted.count { it in visited }}/${highlighted.size} seen")
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.98f)
            .background(CardInk)
            .semantics { contentDescription = "Figure: Nikon D5600 on a turntable. Drag to turn it, tap a part. ${highlighted.joinToString { it.title }}" },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                // The zoom comes before the gestures, so a tap lands where it looks like it lands.
                .graphicsLayer {
                    scaleX = zoom.value
                    scaleY = zoom.value
                    transformOrigin = TransformOrigin(focusX.value, focusY.value)
                }
                .pointerInput(selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = 0f
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            moved += abs(d.x) + abs(d.y)
                            if (moved > 12f && !dragging) {
                                dragging = true
                                if (selected != null) onSelect(null)
                            }
                            if (dragging) {
                                scope.launch {
                                    yaw.snapTo(yaw.value - d.x / size.width * 240f)
                                    pitch.snapTo((pitch.value + d.y / size.height * 120f).coerceIn(8f, 72f))
                                }
                            }
                            change.consume()
                        }
                        if (!dragging) {
                            val hit = painter.hit(down.position.x, down.position.y)
                            if (hit != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onSelect(hit)
                            } else if (selected != null) {
                                onSelect(null)
                            }
                        }
                        dragging = false
                    }
                },
        ) {
            val (cx, cy, s) = layout(size.width.toFloat(), size.height.toFloat())
            val sp = kotlin.math.sin(Math.toRadians(pitch.value.toDouble())).toFloat()
            // The soft halo under the turntable, a little lower than its foot, as if it stood on the card.
            painter.setView(cx, cy, s, HERO_YAW, pitch.value)
            val floor = painter.project(rig.floor)
            val r = rig.floorRadius * s
            blurred(Color.Black.copy(alpha = 0.9f), 16.dp.toPx()) {
                it.drawOval(floor.x - r, floor.y - r * sp + 4.dp.toPx(), floor.x + r, floor.y + r * sp + 8.dp.toPx(), this)
            }
            val lit = highlighted.associateWith { c -> if (c in visited) 0.35f else breath }
            val scene = Scene(shownYaw, pitch.value, HERO_YAW, selected, pressCurve(act.value), thereAndBack(act.value), lit)
            drawIntoCanvas { painter.draw(it.nativeCanvas, cx, cy, s, scene) }
        }
        Text("Fig 1", style = Label.copy(color = Color(0xFFB9C2BA)), modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
        Text("NIKON D5600", style = Label, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
        // The hint gives way to the readout while a part is speaking, so the two never collide.
        androidx.compose.animation.AnimatedVisibility(selected == null, Modifier.align(Alignment.BottomStart), enter = motion.fadeIn, exit = motion.fadeOut) {
            Text("DRAG · TAP A PART", style = Label, modifier = Modifier.padding(14.dp))
        }
        Text(readout, style = Readout.tabular(), modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp))
    }
}

/** What the readout says while a control acts out what it does. */
private fun stateOf(c: Control, t: Float): String = when (c) {
    Control.Shutter -> when {
        t < 0.08f -> "shutter"
        t < 0.42f -> "half-press · focus"
        t < 0.5f -> "release"
        t < 0.78f -> "full press · click"
        else -> "shutter"
    }
    Control.ModeDial -> if (t in 0.2f..0.7f) "mode dial · one click" else "mode dial"
    Control.CommandDial -> if (t in 0.2f..0.7f) "command dial · one step" else "command dial"
    Control.Monitor -> if (t in 0.2f..0.7f) "monitor · swung out" else "monitor"
    Control.Power -> if (t in 0.2f..0.7f) "power · on" else "power · off"
    Control.ZoomRing -> if (t in 0.2f..0.7f) "zoom · 55 mm" else "zoom · 18 mm"
    else -> c.title.lowercase()
}

private fun nearest(current: Float, target: Float): Float {
    var t = target
    while (t - current > 180f) t -= 360f
    while (current - t > 180f) t += 360f
    return t
}

/** Two presses, the second deeper: a half-press, then all the way. */
private fun pressCurve(t: Float): Float = when {
    t < 0.15f -> t / 0.15f * 0.5f
    t < 0.35f -> 0.5f
    t < 0.45f -> 0.5f - (t - 0.35f) / 0.1f * 0.5f
    t < 0.6f -> (t - 0.45f) / 0.15f
    t < 0.75f -> 1f
    else -> (1f - (t - 0.75f) / 0.25f).coerceAtLeast(0f)
}

/** 0 → 1, a held moment, → 0, each move eased like a detent settling. */
private fun thereAndBack(t: Float): Float {
    fun ease(x: Float) = x.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
    return when {
        t < 0.3f -> ease(t / 0.3f)
        t < 0.6f -> 1f
        else -> 1f - ease((t - 0.6f) / 0.3f)
    }
}
