package app.viewfinder.ui.learn

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import app.viewfinder.learn.Insight
import app.viewfinder.learn.Learn
import app.viewfinder.learn.Shot
import app.viewfinder.learn.explain
import app.viewfinder.learn.programFromExif
import app.viewfinder.ui.Dimens
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.RoundIconButton
import app.viewfinder.ui.pressScale
import app.viewfinder.ui.rememberPulse
import app.viewfinder.ui.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Explained(val photo: ImageBitmap?, val aspect: Float, val shot: Shot)

/** A photo from the phone, read back as what its settings did: the settings a lesson teaches, in plain words. */
@Composable
fun ExplainPhotoScreen(uri: Uri, onLesson: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val motion = Motion.current
    val result by produceState<Explained?>(null, uri) { value = read(context, uri) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(0.dp))
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Text("How this was made", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        AnimatedContent(result, transitionSpec = { motion.swap() }, label = "explained") { r ->
            if (r == null) {
                val pulse = rememberPulse(0.05f, 0.1f)
                Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(20.dp)).background(Palette.Text.copy(alpha = pulse)))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    r.photo?.let {
                        Image(it, null, Modifier.fillMaxWidth().aspectRatio(r.aspect.coerceIn(0.6f, 2f)).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                    }
                    r.shot.camera?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary) }
                    val insights = explain(r.shot)
                    if (insights.isEmpty()) {
                        Text(
                            "This photo carries no camera settings. Screenshots, edits and photos sent through chat apps usually lose them. Try one straight from your camera or your phone's camera.",
                            style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary,
                        )
                    } else {
                        insights.forEach { InsightCard(it, onLesson) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InsightCard(insight: Insight, onLesson: (String) -> Unit) {
    val lesson = insight.lesson?.let(Learn::lesson)
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (lesson != null) Modifier.pressScale(interaction, pressed = 0.98f) else Modifier)
            .clip(RoundedCornerShape(Dimens.Radius))
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Dimens.Radius))
            .then(if (lesson != null) Modifier.clickable(interaction, ripple(), role = Role.Button) { onLesson(lesson.id) } else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                insight.label,
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = Palette.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.AccentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(insight.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        if (lesson != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lesson: ${lesson.title}", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.Text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Decodes a preview and reads the EXIF settings of a picked photo. */
private suspend fun read(context: Context, uri: Uri): Explained = withContext(Dispatchers.IO) {
    val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
    fun d(tag: String) = exif?.getAttributeDouble(tag, -1.0)?.takeIf { it > 0 }
    val shot = Shot(
        fNumber = d(ExifInterface.TAG_F_NUMBER),
        seconds = d(ExifInterface.TAG_EXPOSURE_TIME),
        iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)?.takeIf { it > 0 },
        focalMm = d(ExifInterface.TAG_FOCAL_LENGTH),
        flashFired = exif?.getAttributeInt(ExifInterface.TAG_FLASH, -1)?.takeIf { it >= 0 }?.let { it and 1 == 1 },
        program = exif?.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, 0)?.let(::programFromExif),
        // The model already names the brand ("NIKON D5600"); the make only stands in when it's missing.
        camera = (exif?.getAttribute(ExifInterface.TAG_MODEL) ?: exif?.getAttribute(ExifInterface.TAG_MAKE))?.trim()?.takeIf { it.isNotEmpty() },
    )
    var aspect = 1.5f
    val photo = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            aspect = info.size.width.toFloat() / info.size.height
            val scale = (1600f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }.asImageBitmap()
    }.getOrNull()
    Explained(photo, aspect, shot)
}
