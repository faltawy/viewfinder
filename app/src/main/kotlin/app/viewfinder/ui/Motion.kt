package app.viewfinder.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import android.os.Looper
import android.os.Handler
import android.database.ContentObserver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's one motion language. Things that move or resize use springs, so a gesture that interrupts an
 * animation picks it up mid-flight. Things that appear or disappear fade on the emphasized curve: in slower
 * than out. With animations turned off in Android's settings, movement snaps and only short fades remain.
 *
 * Read it with [Motion.current]; never write durations or easings in screens.
 */
@Immutable
class Motion(val reduced: Boolean) {

    /** Position, size and scale: settles quickly with no overshoot. */
    fun <T> move(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 420f)

    /** Press release and small confirmations: a little overshoot, so the control feels physical. */
    fun <T> bounce(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** A press going down: immediate, no overshoot. */
    fun <T> press(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)

    /** Slow, weightless drifts: the mark's corners floating apart when the camera is lost. */
    fun <T> drift(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 40f)

    /** Blending a looping motion in or out, so a loop never starts or stops with a jolt. */
    fun <T> blend(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 60f)

    /**
     * A control acting out what it does (a button pressing, a dial clicking round): steady time, so each stage of
     * the demonstration gets its share. Skipped entirely with reduced motion.
     */
    fun <T> demo(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(DEMO, easing = LinearEasing)

    /** The shutter flash fading out; none at all with reduced motion, it would only be a strobe. */
    fun <T> flash(): FiniteAnimationSpec<T> = tween(FLASH, easing = Decelerate)

    /** Fades and colour changes coming in. */
    fun <T> enter(): FiniteAnimationSpec<T> = tween(if (reduced) FADE_REDUCED else ENTER, easing = Decelerate)

    /** Fades going out: quicker than coming in, so the next thing is never kept waiting. */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(if (reduced) FADE_REDUCED else EXIT, easing = Snappy)

    private fun moveOffset(): FiniteAnimationSpec<IntOffset> = move()
    private fun moveSize(): FiniteAnimationSpec<IntSize> = move()

    val fadeIn: EnterTransition get() = fadeIn(enter())
    val fadeOut: ExitTransition get() = fadeOut(exit())

    /** A small surface arriving: fades in while rising a little. */
    val appear: EnterTransition
        get() = if (reduced) fadeIn else fadeIn + slideInVertically(moveOffset()) { it / 3 }

    val disappear: ExitTransition
        get() = if (reduced) fadeOut else fadeOut + slideOutVertically(moveOffset()) { it / 3 }

    /** A bar arriving from the bottom edge. */
    val riseIn: EnterTransition
        get() = if (reduced) fadeIn else fadeIn + slideInVertically(moveOffset()) { it * 2 }

    val sinkOut: ExitTransition
        get() = if (reduced) fadeOut else fadeOut + slideOutVertically(moveOffset()) { it * 2 }

    /** Content opening in place, pushing what's below down smoothly. */
    val expand: EnterTransition
        get() = if (reduced) fadeIn else fadeIn + expandVertically(moveSize())

    val collapse: ExitTransition
        get() = if (reduced) fadeOut else fadeOut + shrinkVertically(moveSize())

    /** One piece of content replacing another in the same place; the container resizes on a spring. */
    fun swap(): ContentTransform =
        ContentTransform(
            // Fade through: the old content is gone before the new arrives, so text never overlaps.
            targetContentEnter = fadeIn(tween(if (reduced) FADE_REDUCED else ENTER, delayMillis = if (reduced) 0 else EXIT, easing = Decelerate)),
            initialContentExit = fadeOut,
            sizeTransform = SizeTransform(clip = false) { _, _ -> moveSize() },
        )

    /** A small control changing what it shows (one icon becoming another): scales through, size unchanged. */
    fun morph(): ContentTransform =
        if (reduced) {
            ContentTransform(fadeIn, fadeOut, sizeTransform = null)
        } else {
            ContentTransform(
                targetContentEnter = fadeIn(enter()) + scaleIn(move(), initialScale = 0.85f),
                initialContentExit = fadeOut(exit()) + scaleOut(exit(), targetScale = 0.85f),
                sizeTransform = null,
            )
        }

    /** Moving between screens: the new one settles in from slightly smaller. */
    fun <S> screen(scope: AnimatedContentTransitionScope<S>): ContentTransform = with(scope) {
        if (reduced) {
            fadeIn togetherWith fadeOut
        } else {
            (fadeIn(tween(SCREEN, easing = Decelerate)) + scaleIn(move(), initialScale = 0.96f)) togetherWith fadeOut
        }
    }

    /** Delay before the [index]th item of a batch appears, so a group arrives as a wave. */
    fun staggerMillis(index: Int): Int = if (reduced) 0 else index.coerceIn(0, STAGGER_CAP) * STAGGER_STEP

    companion object {
        const val ENTER = 240
        const val EXIT = 140
        const val SCREEN = 280
        private const val FLASH = 360
        private const val FADE_REDUCED = 120
        private const val STAGGER_STEP = 28
        private const val STAGGER_CAP = 12

        /** Loading placeholders breathing; one full in-out cycle is twice this. */
        const val PULSE = 1_000
        /** The mark's loops, one full cycle each: corners hunting, the arc circling the ring, the dot's working beat. */
        const val HUNT = 1_400
        const val SWEEP = 1_200
        const val BEAT = 1_000
        /** The connect screen's slowly drifting background. */
        const val DRIFT = 18_000

        /** Loading placeholders breathing in and out, on a sine-like curve. */
        fun <T> pulseLoop(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(PULSE, easing = Breathe), RepeatMode.Reverse)

        /** A ring spreading out from a spot that wants a tap, then starting again. */
        fun <T> rippleLoop(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(RIPPLE, easing = Decelerate), RepeatMode.Restart)
        const val RIPPLE = 1_800

        /** Something resting that sways a little, as if held in the air; one way is this long. */
        fun <T> swayLoop(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(SWAY, easing = Breathe), RepeatMode.Reverse)
        const val SWAY = 4_200
        const val DEMO = 1_700

        /** The connect screen's background drifting, too slowly to notice moving. */
        fun <T> driftLoop(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(DRIFT, easing = LinearEasing), RepeatMode.Reverse)

        private val Breathe = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

        /** Strong ease-outs: things react at once and settle softly. Nothing in the app eases in. */
        val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val Snappy = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

        val current: Motion
            @Composable @ReadOnlyComposable get() = LocalMotion.current
    }
}

val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }

/** Reads Android's "remove animations" setting once, when the theme is first composed. */
@Composable
internal fun rememberMotion(): Motion {
    val context = LocalContext.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    // Follows the setting while the app is open, not just at launch.
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return remember(reduced) { Motion(reduced = reduced) }
}
