package app.viewfinder.ui.learn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.viewfinder.learn.Control
import app.viewfinder.learn.Mode
import app.viewfinder.learn.RecipeStep
import app.viewfinder.learn.Step
import app.viewfinder.ui.Dimens
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.pressScale
import app.viewfinder.ui.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets the step's slide-in settle before the line glides, so the glide is seen. */
private const val GLIDE_AFTER_MS = 350L

/** A recipe: the result as a before/after photo, the steps that make it lighting the camera, then what to expect. */
@Composable
internal fun RecipeStepContent(step: Step.Recipe) {
    var active by rememberSaveable(step.title) { mutableIntStateOf(-1) }
    var visited by remember { mutableStateOf(emptySet<Control>()) }
    val controls = remember(step) { step.steps.mapNotNull { it.control }.distinct() }
    val selected = step.steps.getOrNull(active)?.control

    fun activate(i: Int) {
        active = if (active == i) -1 else i
        step.steps.getOrNull(active)?.control?.let { visited = visited + it }
    }

    BeforeAfter(step.look.photo, before = { LookLayer(step.look, after = false) }, after = { LookLayer(step.look, after = true) })
    Title(step.title)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        step.steps.forEachIndexed { i, s -> Numbered(i + 1, s.text, active = i == active) { activate(i) } }
    }
    if (controls.isNotEmpty()) {
        FigureCard {
            CameraBody(
                highlighted = controls,
                visited = visited,
                selected = selected,
                onSelect = { c ->
                    // A part tapped on the figure picks the first step done with it; tapping it again, or away, clears.
                    active = if (c == null || c == selected) -1 else step.steps.indexOfFirst { it.control == c }
                    if (c != null) visited = visited + c
                },
                mode = step.mode ?: Mode.Auto,
                cue = active,
            )
        }
    }
    Note("Expect", step.expect, Palette.Accent)
    Note("Won't work when", step.failsWhen, Palette.Muted)
}

@Composable
private fun Note(label: String, body: String, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
    }
}

/**
 * A photo split by a line you drag: [before] on the left of it, [after] on the right. The line glides once from a
 * third of the way in to the middle so it reads as something to move; horizontal drags move it, vertical ones
 * still scroll the page.
 */
@Composable
internal fun BeforeAfter(photo: String, before: @Composable BoxScope.() -> Unit, after: @Composable BoxScope.() -> Unit) {
    val motion = Motion.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var split by rememberSaveable(photo) { mutableFloatStateOf(if (motion.reduced) 0.5f else 0.35f) }
    var glided by rememberSaveable(photo) { mutableStateOf(motion.reduced) }
    var glide by remember { mutableStateOf<Job?>(null) }
    // The cue to drag plays once the photo is there to see and the step has slid in, never again after.
    val loaded = rememberLearnPhoto(photo) != null
    LaunchedEffect(loaded) {
        if (loaded && !glided) {
            glide = scope.launch {
                delay(GLIDE_AFTER_MS)
                glided = true
                animate(split, 0.5f, animationSpec = motion.move()) { v, _ -> split = v }
            }
        }
    }

    CreditedPhoto(
        photo,
        Modifier
            .pointerInput(photo) {
                detectHorizontalDragGestures(onDragStart = { glide?.cancel(); glided = true }) { change, dx ->
                    change.consume()
                    val was = split
                    split = (split + dx / size.width).coerceIn(0f, 1f)
                    if (split != was && (split == 0f || split == 1f)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            .semantics {
                contentDescription = "Before and after. Drag to compare."
                stateDescription = "${(split * 100).roundToInt()} percent before"
                progressBarRangeInfo = ProgressBarRangeInfo(split, 0f..1f)
                setProgress { v -> split = v.coerceIn(0f, 1f); true }
            },
    ) {
        before()
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(left = size.width * split) { this@drawWithContent.drawContent() } },
        ) { after() }
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split
            drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            // The handle sits low, out of the way of the subject most photos put near the middle.
            val handle = Offset(x, size.height * 0.84f)
            drawCircle(Color.Black.copy(alpha = 0.55f), 16.dp.toPx(), handle)
            drawCircle(Color.White.copy(alpha = 0.9f), 16.dp.toPx(), handle, style = Stroke(1.5.dp.toPx()))
        }
        Badge("BEFORE", Modifier.align(Alignment.TopStart))
        Badge("AFTER · ≈ PREVIEW", Modifier.align(Alignment.TopEnd))
    }
}
