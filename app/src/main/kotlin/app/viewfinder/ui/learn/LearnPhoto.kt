package app.viewfinder.ui.learn

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.rememberPulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The bundled Learn photos (assets/learn), decoded once and kept while there's room. */
object LearnPhotos {
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var credits: Map<String, String>? = null

    /** [name] is a file in assets/learn, with or without ".jpg". */
    suspend fun load(context: Context, name: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = if ('.' in name) name else "$name.jpg"
        cache.get(file) ?: runCatching {
            context.assets.open("learn/$file").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()?.also { cache.put(file, it) }
    }

    /** "Photo: Joshua Earle, Unsplash", from credits.json. */
    fun credit(context: Context, name: String): String? {
        val all = credits ?: runCatching {
            val json = JSONObject(context.assets.open("learn/credits.json").bufferedReader().use { it.readText() })
            val photos = json.getJSONArray("photos")
            (0 until photos.length()).associate { i ->
                val p = photos.getJSONObject(i)
                p.getString("file").removeSuffix(".jpg") to p.getString("author")
            }
        }.getOrDefault(emptyMap()).also { credits = it }
        return all[name.removeSuffix(".jpg")]?.let { "Photo: $it, Unsplash" }
    }
}

/** An asset photo as state: null until decoded. */
@Composable
fun rememberLearnPhoto(name: String): ImageBitmap? {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, name) { value = LearnPhotos.load(context, name) }
    return photo
}

/**
 * A bundled photo at its own 3:2 shape, its space held by a breathing placeholder until it decodes, so nothing jumps.
 * [content] draws on top (playground overlays).
 */
@Composable
fun LearnPhoto(
    name: String,
    modifier: Modifier = Modifier,
    aspect: Float = 1.5f,
    contentScale: ContentScale = ContentScale.Crop,
    colorFilter: ColorFilter? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val photo = rememberLearnPhoto(name)
    val shown by animateFloatAsState(if (photo != null) 1f else 0f, Motion.current.enter(), label = "photo-in")
    val pulse = rememberPulse(0.05f, 0.1f)
    Box(modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(20.dp)).background(Palette.Text.copy(alpha = pulse))) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown }, contentScale = contentScale, colorFilter = colorFilter)
        }
        content()
    }
}

/** The photographer's name, small and quiet, under a photo. */
@Composable
fun Credit(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val credit = remember(name) { LearnPhotos.credit(context, name) } ?: return
    Text(credit, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = modifier.padding(top = 6.dp))
}

/** A photo with its credit underneath. */
@Composable
fun CreditedPhoto(name: String, modifier: Modifier = Modifier, aspect: Float = 1.5f, content: @Composable BoxScope.() -> Unit = {}) {
    Column(modifier) {
        LearnPhoto(name, aspect = aspect, content = content)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Credit(name) }
    }
}
