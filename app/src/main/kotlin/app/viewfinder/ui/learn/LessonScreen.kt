package app.viewfinder.ui.learn

import app.viewfinder.learn.Learn

import androidx.compose.material.icons.automirrored.rounded.ArrowForward

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import app.viewfinder.learn.Control
import app.viewfinder.learn.Lesson
import app.viewfinder.learn.Step
import app.viewfinder.ui.AccentButton
import app.viewfinder.ui.Dimens
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.QuietButton
import app.viewfinder.ui.RoundIconButton
import app.viewfinder.ui.pressScale
import app.viewfinder.ui.tabular

/** One lesson, a step at a time: read, touch, answer, then a recap. Nothing is scored. */
@Composable
fun LessonScreen(lesson: Lesson, onClose: () -> Unit, onFinish: () -> Unit, onOpenLesson: (String) -> Unit) {
    val motion = Motion.current
    var index by rememberSaveable(lesson.id) { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    val last = index == lesson.steps.lastIndex
    // Each step keeps what you did on it (an answer, a line you dragged, a step you picked) when you go Back to it.
    val saved = rememberSaveableStateHolder()

    Column(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.Close, "Close lesson", onClose)
            Spacer(Modifier.width(12.dp))
            StepBar(lesson.steps.size, index, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text("${index + 1}/${lesson.steps.size}", style = MaterialTheme.typography.labelMedium.tabular(), color = Palette.TextSecondary)
            Spacer(Modifier.width(8.dp))
        }

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                if (motion.reduced) {
                    ContentTransform(fadeIn(motion.enter()), fadeOut(motion.exit()), sizeTransform = null)
                } else {
                    ContentTransform(
                        fadeIn(motion.enter()) + slideInHorizontally(motion.move()) { it / 5 * dir },
                        fadeOut(motion.exit()) + slideOutHorizontally(motion.move()) { -it / 5 * dir },
                        sizeTransform = SizeTransform(clip = false),
                    )
                }
            },
            label = "lesson-step",
            modifier = Modifier.weight(1f),
        ) { i ->
            saved.SaveableStateProvider("${lesson.id}:$i") {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StepContent(lesson, lesson.steps[i], onOpenLesson)
                Spacer(Modifier.height(12.dp))
            }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(index > 0, enter = motion.fadeIn, exit = motion.fadeOut) {
                QuietButton("Back", { forward = false; index-- })
            }
            Spacer(Modifier.weight(1f))
            AccentButton(
                if (last) "Finish" else "Continue",
                {
                    if (last) onFinish() else {
                        forward = true
                        index++
                    }
                },
            )
        }
    }
}

/** Thin segments, one per step, filling with the accent as you go. */
@Composable
private fun StepBar(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { k ->
            val fill by animateColorAsState(if (k <= index) Palette.Accent else Palette.Text.copy(alpha = 0.12f), Motion.current.enter(), label = "seg")
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(fill))
        }
    }
}

@Composable
internal fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
}

@Composable
private fun StepContent(lesson: Lesson, step: Step, onOpenLesson: (String) -> Unit) {
    when (step) {
        is Step.Read -> {
            step.photo?.let { CreditedPhoto(it) }
            Title(step.title)
            Body(step.body)
        }
        is Step.Play -> {
            Title(step.title)
            Body(step.prompt)
            PlaygroundFor(step.playground)
        }
        is Step.OnCamera -> OnCameraStep(step)
        is Step.Recipe -> RecipeStepContent(step)
        is Step.Dial -> {
            Title(step.title)
            Body(step.body)
            ModeDialPlayground()
        }
        is Step.Ask -> AskStep(step)
        is Step.TryIt -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CameraAlt, null, tint = Palette.Accent, modifier = Modifier.size(Dimens.IconSm))
                }
                Spacer(Modifier.width(12.dp))
                Text("Try it outside", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
            Title(step.title)
            step.tasks.forEachIndexed { i, task -> Numbered(i + 1, task) }
        }
        is Step.Recap -> {
            Text("You've got it", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            Title(lesson.title)
            step.points.forEach { point ->
                Row {
                    Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Palette.Accent, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(point, style = MaterialTheme.typography.bodyLarge)
                }
            }
            lesson.why?.let(Learn::lesson)?.let { why -> WhyLink(why, onOpenLesson) }
        }
    }
}

/** A numbered line. With [onClick] it's tappable, and [active] lights its number in the accent. */
@Composable
internal fun Numbered(n: Int, text: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val motion = Motion.current
    val disc by animateColorAsState(if (active) Palette.Accent else Palette.Text.copy(alpha = 0.08f), motion.enter(), label = "num-disc")
    val digit by animateColorAsState(if (active) Palette.OnAccent else Palette.Text, motion.enter(), label = "num-digit")
    val ink by animateColorAsState(if (active) Palette.Text else Palette.TextSecondary, motion.enter(), label = "num-ink")
    val interaction = remember { MutableInteractionSource() }
    Row(
        if (onClick == null) Modifier else Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.98f)
            .clip(RoundedCornerShape(Dimens.Radius))
            .semantics { this.selected = active }
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium.tabular(), color = digit)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The camera with this step's controls glowing; tap one (or its chip) to see it act and read what it does. */
@Composable
private fun OnCameraStep(step: Step.OnCamera) {
    val motion = Motion.current
    var selected by remember { mutableStateOf<Control?>(null) }
    var visited by remember { mutableStateOf(emptySet<Control>()) }
    fun pick(c: Control?) {
        selected = if (c == null || selected == c) null else c
        if (c != null) visited = visited + c
    }
    Title(step.title)
    Body(step.body)
    FigureCard { CameraBody(step.controls, visited, selected, { pick(it) }) }
    // Chips for every control in the step: a way in that doesn't depend on finding the spot.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        step.controls.forEach { c -> ControlChip(c, c == selected, c in visited) { pick(c) } }
    }
    AnimatedContent(selected, transitionSpec = { motion.swap() }, label = "control-card") { c ->
        if (c == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TouchApp, null, tint = Palette.Muted, modifier = Modifier.size(Dimens.IconSm))
                Spacer(Modifier.width(8.dp))
                Text("Tap a glowing spot, or drag to turn the camera.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.Radius))
                    .background(Palette.Raised)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                Text(c.what, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
        }
    }
}

/** The bordered card the camera figure sits in. */
@Composable
internal fun FigureCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusLg))
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Dimens.RadiusLg)),
    ) { content() }
}

/** Under a recap: the lesson that explains why this one works. Opening it counts this lesson as done. */
@Composable
private fun WhyLink(why: Lesson, onOpenLesson: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(Dimens.Radius))
            .clickable(interaction, ripple(), role = Role.Button) { onOpenLesson(why.id) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Why this works: ${why.title}", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.Text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ControlChip(control: Control, on: Boolean, seen: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) Palette.Accent else Palette.Text.copy(alpha = 0.07f), Motion.current.enter(), label = "cchip")
    val ink by animateColorAsState(if (on) Palette.OnAccent else Palette.Text, Motion.current.enter(), label = "cchip-ink")
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .semantics { this.selected = on }
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The "seen" dot's space is always there, so marking a control seen never reflows the chips.
        val dot by animateFloatAsState(if (seen && !on) 1f else 0f, Motion.current.enter(), label = "seen")
        Box(Modifier.size(6.dp).graphicsLayer { alpha = dot; scaleX = 0.5f + 0.5f * dot; scaleY = 0.5f + 0.5f * dot }.clip(CircleShape).background(Palette.Accent))
        Spacer(Modifier.width(6.dp))
        Text(control.title, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** A question: pick an option and the best answer and the reason appear, whatever you picked. */
@Composable
private fun AskStep(step: Step.Ask) {
    val motion = Motion.current
    var picked by rememberSaveable(step.question) { mutableStateOf<Int?>(null) }
    step.photo?.let { CreditedPhoto(it) }
    Text(step.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        step.options.forEachIndexed { i, option ->
            val answered = picked != null
            val right = i == step.answer
            val border by animateColorAsState(
                when {
                    answered && right -> Palette.Accent
                    answered && i == picked -> Palette.Muted
                    else -> Palette.Hairline
                },
                motion.enter(), label = "opt-border",
            )
            val fill by animateColorAsState(if (answered && right) Palette.AccentSoft else Palette.Surface, motion.enter(), label = "opt-fill")
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(interaction, pressed = 0.98f)
                    .clip(RoundedCornerShape(Dimens.Radius))
                    .background(fill)
                    .border(1.5.dp, border, RoundedCornerShape(Dimens.Radius))
                    .clickable(interaction, ripple(), enabled = !answered, role = Role.RadioButton) { picked = i }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AnimatedVisibility(answered && right, enter = motion.fadeIn, exit = motion.fadeOut) {
                    Icon(Icons.Rounded.Check, "Best answer", tint = Palette.Accent, modifier = Modifier.size(Dimens.Icon))
                }
            }
        }
    }
    AnimatedVisibility(picked != null, enter = motion.expand, exit = motion.collapse) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (picked == step.answer) "Exactly." else "Not quite, and that's how it sticks.",
                style = MaterialTheme.typography.titleMedium,
                color = if (picked == step.answer) Palette.Accent else Palette.Text,
            )
            Text(step.why, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
        }
    }
}
