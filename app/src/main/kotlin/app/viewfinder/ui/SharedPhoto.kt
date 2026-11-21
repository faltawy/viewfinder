package app.viewfinder.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** The app-wide shared-transition scope, set up around the screen switcher in MainActivity. */
val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** The screen switcher's visibility scope for the screen being composed. */
val LocalScreenVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks the image of photo [handle], so the same photo on the next screen grows out of it (gallery tile to
 * viewer) and shrinks back into it. Does nothing outside the screen switcher or with reduced motion.
 */
@Composable
fun Modifier.sharedPhoto(handle: Long): Modifier = shared("photo-$handle")

/** Marks the [ViewfinderMark], so it flies from screen to screen (connect, gallery header, remote) rather than fading. */
@Composable
fun Modifier.sharedMark(): Modifier = shared("mark")

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.shared(key: String): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val visibility = LocalScreenVisibility.current ?: return this
    val motion = Motion.current
    if (motion.reduced) return this
    return with(shared) {
        this@shared.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> motion.move() },
        )
    }
}
