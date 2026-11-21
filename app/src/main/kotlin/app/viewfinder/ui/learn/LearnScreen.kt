package app.viewfinder.ui.learn

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.viewfinder.learn.Learn
import app.viewfinder.learn.Lesson
import app.viewfinder.learn.nextLesson
import app.viewfinder.ui.AccentButton
import app.viewfinder.ui.Dimens
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.RoundIconButton
import app.viewfinder.ui.pressScale
import app.viewfinder.ui.rememberPulse

/**
 * The learning path: one calm list of lessons, the next one waiting at the top. No streaks, no points; a
 * finished lesson simply gets a tick, and you can open any lesson at any time.
 */
@Composable
fun LearnScreen(done: Set<String>, onOpen: (Lesson) -> Unit, onExplain: (Uri) -> Unit, onBack: () -> Unit) {
    val next = nextLesson(Learn.chapters, done)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onExplain(uri) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        // A faint jade glow at the top, so the page has some light to it.
        Canvas(Modifier.fillMaxWidth().height(360.dp)) {
            drawRect(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * 0.2f, 0f), size.width * 0.9f))
        }
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.height(20.dp))
            Text("Learn photography", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(
                "Small lessons with real photos you can touch. No camera needed: try things here, then out in the world.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(24.dp))

            if (next != null) {
                UpNext(next, started = done.isNotEmpty()) { onOpen(next) }
            } else {
                AllDone(pick)
            }
            Spacer(Modifier.height(32.dp))

            Learn.chapters.forEachIndexed { ci, chapter ->
                Text(chapter.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                chapter.lessons.forEachIndexed { li, lesson ->
                    val isFirst = ci == 0 && li == 0
                    val isLast = ci == Learn.chapters.lastIndex && li == chapter.lessons.lastIndex
                    PathRow(lesson, lesson.id in done, lesson == next, isFirst, isLast) { onOpen(lesson) }
                }
                Spacer(Modifier.height(24.dp))
            }

            ExplainCard(pick)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The next lesson, big: its real photo, what it's about, and one button. */
@Composable
private fun UpNext(lesson: Lesson, started: Boolean, onStart: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.98f)
            .clip(RoundedCornerShape(Dimens.RadiusLg))
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Dimens.RadiusLg))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onStart),
    ) {
        LearnPhoto(lesson.cover, aspect = 1.8f) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            Text(
                if (started) "Up next" else "Start here",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.OnAccent,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(50)).background(Palette.Accent).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
                Text("${lesson.minutes} min", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            }
        }
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lesson.summary, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            AccentButton("Begin", onStart, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun AllDone(onExplain: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusLg))
            .background(Palette.AccentSoft)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("You've walked the whole path", style = MaterialTheme.typography.titleLarge)
        Text(
            "Now the best teacher is your own photos. Pick one and see what the camera did, then open any lesson again whenever you like.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
        )
        AccentButton("Explain a photo", onExplain, icon = Icons.Rounded.PhotoLibrary)
    }
}

/** A lesson on the path: a node on the line (ticked when done, glowing when next), its photo, and its name. */
@Composable
private fun PathRow(lesson: Lesson, done: Boolean, next: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val glow = if (next) rememberPulse(0.15f, 0.4f, "next-node") else 0f
    val lit by animateFloatAsState(if (done) 1f else 0f, Motion.current.enter(), label = "node")
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .pressScale(interaction, pressed = 0.98f)
            .clip(RoundedCornerShape(Dimens.Radius))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (done) "Done" else if (next) "Up next" else "Not started" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The path line and its node.
        Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
                if (!first) drawLine(Palette.Hairline.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height / 2), 2.dp.toPx(), pathEffect = dash)
                if (!last) drawLine(Palette.Hairline.copy(alpha = 0.5f), Offset(x, size.height / 2), Offset(x, size.height), 2.dp.toPx(), pathEffect = dash)
                if (next) drawCircle(Palette.Accent.copy(alpha = glow), 16.dp.toPx(), center)
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (done) Palette.Accent else if (next) Palette.Background else Palette.Raised)
                    .border(2.dp, if (done || next) Palette.Accent else Palette.Hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, tint = Palette.OnAccent, modifier = Modifier.size(14.dp).graphicsLayer { alpha = lit; scaleX = 0.6f + 0.4f * lit; scaleY = 0.6f + 0.4f * lit })
            }
        }
        Spacer(Modifier.width(10.dp))
        LearnPhoto(lesson.cover, Modifier.width(76.dp).padding(vertical = 10.dp), aspect = 1.2f)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(lesson.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(lesson.summary, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text("${lesson.minutes} min", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}

/** Pick a photo from the phone and have its settings read back in plain words. */
@Composable
private fun ExplainCard(onPick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.98f)
            .clip(RoundedCornerShape(Dimens.RadiusLg))
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Dimens.RadiusLg))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onPick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Accent)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Explain a photo", style = MaterialTheme.typography.titleMedium)
            Text("Pick any photo on your phone and see how it was made, in plain words.", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}
