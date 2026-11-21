package app.viewfinder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What the app is doing, as the mark shows it. */
enum class MarkState {
    /** At rest. */
    Idle,
    /** Corners hunt like autofocus, an arc circles the ring, the dot dims a little. */
    Searching,
    /** Arriving here from another state, the corners lock in and the dot lights once: done, ready. */
    Connected,
    /** The dot pulses: reading the card, saving, a long exposure. */
    Working,
    /** Corners drift apart and fade, the dot goes grey. */
    Lost,
}

/**
 * The Shutter + Frame mark (the launcher icon, `tools/gen_launcher_icon.py`), alive. Each change of [state]
 * moves it to that state's pose on a spring; [shots] going up presses the dot in with a flash.
 * Decorative: the text around it says the same thing. Every animated value is read only while drawing,
 * so the loops never recompose anything. With reduced motion each state is a still pose.
 */
@Composable
fun ViewfinderMark(state: MarkState, modifier: Modifier = Modifier, shots: Int = 0) {
    val motion = Motion.current
    val loops = !motion.reduced

    val spread = remember { Animatable(restSpread(state)) }
    val dome = remember { Animatable(1f) }
    val flash = remember { Animatable(1f) }   // shutter flash progress, 1 = over
    val lockGlow = remember { Animatable(0f) }
    val alpha = animateFloatAsState(if (state == MarkState.Lost) 0.4f else 1f, motion.move(), label = "mark-alpha")
    val dim = animateFloatAsState(
        when (state) {
            MarkState.Searching -> 0.35f
            MarkState.Lost -> 1f
            else -> 0f
        },
        motion.move(), label = "mark-dim",
    )
    val hunt = animateFloatAsState(if (loops && state == MarkState.Searching) 1f else 0f, motion.blend(), label = "mark-hunt")
    val beat = animateFloatAsState(if (loops && state == MarkState.Working) 1f else 0f, motion.blend(), label = "mark-beat")
    val wander = animateFloatAsState(if (loops && state == MarkState.Lost) 1f else 0f, motion.blend(), label = "mark-wander")

    // Seconds, for the loops only; it keeps counting across states so a loop never restarts from zero.
    val clock = remember { mutableFloatStateOf(0f) }
    val looping = loops && state in LoopingStates
    LaunchedEffect(looping) {
        val base = clock.floatValue
        var start = -1L
        while (true) {
            val elapsed = withFrameNanos { now ->
                if (start < 0) start = now
                ((now - start) / 1e9f).also { clock.floatValue = base + it }
            }
            if (!looping && elapsed > BLEND_OUT_S) break   // let the loop fade out, then stop asking for frames
        }
    }

    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val from = previous
        previous = state
        if (from == state) return@LaunchedEffect   // first composition: start in the pose, don't arrive in it
        when (state) {
            MarkState.Connected -> {
                // Snap inward, so the lock overshoots a touch and settles; the dot's one bright beat.
                launch { spread.animateTo(0f, motion.bounce(), initialVelocity = if (loops) LOCK_VELOCITY else 0f) }
                if (loops) {
                    lockGlow.snapTo(LOCK_GLOW)
                    lockGlow.animateTo(0f, motion.flash())
                }
            }
            MarkState.Lost -> spread.animateTo(restSpread(state), motion.drift())
            else -> spread.animateTo(restSpread(state), motion.move())
        }
    }

    var lastShots by remember { mutableIntStateOf(shots) }
    LaunchedEffect(shots) {
        if (shots <= lastShots) {
            lastShots = shots
            return@LaunchedEffect
        }
        lastShots = shots
        if (!loops) return@LaunchedEffect   // no strobe with reduced motion
        launch {
            flash.snapTo(0f)
            flash.animateTo(1f, motion.flash())
        }
        dome.animateTo(PRESSED, motion.press())
        dome.animateTo(1f, motion.bounce())
    }

    Spacer(
        modifier.drawWithCache {
            val u = size.minDimension / GRID_SPAN
            val ox = (size.width - GRID_SPAN * u) / 2
            val oy = (size.height - GRID_SPAN * u) / 2
            fun p(v: Float, o: Float) = o + (v - GRID_ORIGIN) * u
            val c = Offset(p(60f, ox), p(60f, oy))
            val metal = Brush.linearGradient(
                0f to Color(0xFFFFFFFF), 0.45f to Color(0xFFE4EAE5), 0.7f to Color(0xFFAEB8B1), 1f to Color(0xFFEEF2EE),
                start = Offset(p(20f, ox), p(18f, oy)), end = Offset(p(100f, ox), p(104f, oy)),
            )
            val corners = cornerPaths(p(24f, ox), p(24f, oy), p(96f, ox), p(96f, oy), 14f * u, 4f * u)
            val cornerStroke = Stroke(5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ringStroke = Stroke(3.5f * u)
            val sweepStroke = Stroke(3.5f * u, cap = StrokeCap.Round)
            val well = 25f * u
            val ring = 23.25f * u

            onDrawBehind {
                val t = clock.floatValue
                val h = hunt.value
                val b = beat.value
                val w = wander.value
                val d = dim.value.coerceIn(0f, 1f)
                val pulse = 0.5f - 0.5f * cos(2 * PI.toFloat() * t * 1000f / Motion.BEAT)
                val f = flash.value
                val glow = min(2.5f, 1f + b * 0.6f * pulse + lockGlow.value + 1.4f * (1 - f) * (1 - f) * (1 - f) * (1 - f))

                // Glow, well, ring.
                val glowAlpha = (glow / 2.5f * 1.6f).coerceAtMost(1f) * (1 - d)
                if (glowAlpha > 0.01f) {
                    val gr = well * (1.15f + 0.12f * glow)
                    drawCircle(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.55f), Color.Transparent), c, gr), gr, c, alpha = glowAlpha)
                }
                drawCircle(Well, well, c)
                drawCircle(metal, ring, c, style = ringStroke)
                if (h > 0.01f) {
                    val angle = (t * 1000f / Motion.SWEEP) * 360f % 360f
                    drawArc(
                        DomeLight, angle - 90f, 0.16f * 360f, useCenter = false,
                        topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2),
                        alpha = 0.9f * h, style = sweepStroke,
                    )
                }

                // The jade dome, its highlight, and the shutter flash spreading from it.
                val dr = 17.5f * u * dome.value * (1 - b * 0.035f * pulse)
                drawCircle(
                    Brush.radialGradient(
                        0f to lerp(DomeLight, GreyLight, d), 0.55f to lerp(Palette.Accent, GreyMid, d), 1f to lerp(DomeDeep, GreyDeep, d),
                        center = Offset(c.x - dr * 0.2f, c.y - dr * 0.32f), radius = dr * 1.4f,
                    ),
                    dr, c,
                )
                val hl = Offset(c.x - dr * 0.31f, c.y - dr * 0.43f)
                rotate(-30f, hl) {
                    drawOval(Color.White, Offset(hl.x - dr * 0.4f, hl.y - dr * 0.2f), Size(dr * 0.8f, dr * 0.4f), alpha = 0.28f)
                }
                if (f < 1f) {
                    drawCircle(
                        FlashRing, radius = dr + (38.75f * u - dr) * f, center = c, alpha = (1 - f) * 0.85f,
                        style = Stroke((3.5f + (0.6f - 3.5f) * f) * u),
                    )
                }

                // The four corners, hunting or drifting along their diagonals.
                val s = spread.value + h * 3.5f * sin(2 * PI.toFloat() * t * 1000f / Motion.HUNT)
                corners.forEachIndexed { i, (dir, path) ->
                    val wx = w * 1.2f * sin(t * 1.7f + i * 2.1f)
                    val wy = w * 1.2f * cos(t * 1.3f + i * 1.4f)
                    translate((dir.x * s + wx) * u, (dir.y * s + wy) * u) {
                        drawPath(path, metal, alpha = alpha.value, style = cornerStroke)
                    }
                }
            }
        },
    )
}

/** How far the corners stand out, in grid units, once a state has settled. */
private fun restSpread(state: MarkState) = when (state) {
    MarkState.Searching -> 3.5f
    MarkState.Lost -> 8f
    else -> 0f
}

/** Each corner bracket with the diagonal it moves along. */
private fun cornerPaths(lx: Float, ly: Float, hx: Float, hy: Float, arm: Float, bend: Float) = listOf(
    Offset(-1f, -1f) to Path().apply { moveTo(lx, ly + arm); lineTo(lx, ly + bend); quadraticTo(lx, ly, lx + bend, ly); lineTo(lx + arm, ly) },
    Offset(1f, -1f) to Path().apply { moveTo(hx - arm, ly); lineTo(hx - bend, ly); quadraticTo(hx, ly, hx, ly + bend); lineTo(hx, ly + arm) },
    Offset(1f, 1f) to Path().apply { moveTo(hx, hy - arm); lineTo(hx, hy - bend); quadraticTo(hx, hy, hx - bend, hy); lineTo(hx - arm, hy) },
    Offset(-1f, 1f) to Path().apply { moveTo(lx + arm, hy); lineTo(lx + bend, hy); quadraticTo(lx, hy, lx, hy - bend); lineTo(lx, hy - arm) },
)

private val LoopingStates = setOf(MarkState.Searching, MarkState.Working, MarkState.Lost)

/** The 120-unit design grid, cropped to the corners at rest: the mark fills its box when still. */
private const val GRID_ORIGIN = 21.5f
private const val GRID_SPAN = 77f
private const val LOCK_VELOCITY = -40f   // grid units per second, inward
private const val LOCK_GLOW = 1.1f
private const val PRESSED = 0.8f
private const val BLEND_OUT_S = 1.5f

private val Well = Color(0xFF0A0D0B)
private val DomeLight = Color(0xFF8FE9BE)
private val DomeDeep = Color(0xFF257552)
private val GreyLight = Color(0xFFA3ACA6)
private val GreyMid = Color(0xFF5E6862)
private val GreyDeep = Color(0xFF2A302C)
private val FlashRing = Color(0xFFDFFBEA)
