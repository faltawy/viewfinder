package app.viewfinder.ui.learn

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.viewfinder.learn.Look
import app.viewfinder.learn.MonoFilter
import app.viewfinder.learn.MonoWeights
import app.viewfinder.learn.monoWeights
import app.viewfinder.learn.whiteBalanceGains

/**
 * One side of a recipe's before/after: [after] false is what you'd get without the recipe, true is its result.
 * Fills its box; drawn from the look's own photo with the playgrounds' layers, so a recipe and the lesson that
 * explains it show the same thing.
 */
@Composable
fun LookLayer(look: Look, after: Boolean) {
    when (look) {
        Look.SoftBackground -> ApertureLayers(apertureOpen(if (after) 2.8 else 16.0))
        Look.FreezeVsFlow -> TrafficLayers(if (after) 1 / 15f else 1 / 1000f)
        Look.Warm -> {
            // The photo is already golden; without the Shade preset, Auto would have cooled it toward neutral.
            val photo = rememberLearnPhoto(look.photo)
            Layer(photo, colorFilter = if (after) null else wbFilter(whiteBalanceGains(3600, 5200)))
        }
        Look.MonoRed -> {
            val photo = rememberLearnPhoto(look.photo)
            Layer(photo, colorFilter = if (after) remember { monoFilter(monoWeights(MonoFilter.Red), 1.25f) } else null)
        }
        Look.Vivid -> {
            val photo = rememberLearnPhoto(look.photo)
            Layer(photo, colorFilter = if (after) remember { ColorFilter.colorMatrix(saturation(1.45f).also { it.timesAssign(contrast(1.12f)) }) } else null)
        }
        Look.Miniature -> {
            val photo = rememberLearnPhoto(look.photo)
            if (!after) {
                Layer(photo)
            } else {
                val punch = remember { ColorFilter.colorMatrix(saturation(1.3f).also { it.timesAssign(contrast(1.1f)) }) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = with(LocalDensity.current) { maxWidth.toPx() }
                    Layer(photo, colorFilter = punch)
                    // A blurred copy on top, cut away along a sharp band: the shallow focus of a model shot up close.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(MINIATURE_MASK, blendMode = BlendMode.DstIn)
                            },
                    ) { Layer(photo, blurX = w * 0.012f, colorFilter = punch) }
                }
            }
        }
        Look.PopColour -> {
            val photo = rememberLearnPhoto(look.photo)
            if (!after) Layer(photo) else PopLayer(photo, targetDeg = 6f, widthDeg = 20f)
        }
        Look.Silhouette -> {
            val photo = rememberLearnPhoto(look.photo)
            if (!after) {
                // Metered for the subject: a stop brighter than the photo, so her face reads.
                SensorLayer(photo, gain = 2f, sigma = 0f)
            } else {
                // Metered for the sky: everything darker than it sinks to black, the sky itself stays bright.
                Layer(photo, colorFilter = remember { ColorFilter.colorMatrix(contrast(1.8f, pivot = 0.75f)) })
            }
        }
    }
}

/** Sharp across 55 % of the height (±8 %), fully blurred beyond ±25 %. */
private val MINIATURE_MASK = Brush.verticalGradient(
    0f to Color.Black,
    0.30f to Color.Black,
    0.47f to Color.Transparent,
    0.63f to Color.Transparent,
    0.80f to Color.Black,
    1f to Color.Black,
)

/** A grey made of [w], with [contrast] about mid-grey: out = contrast × (w·rgb − 0.5) + 0.5. */
private fun monoFilter(w: MonoWeights, contrast: Float): ColorFilter {
    val off = 0.5f * (1f - contrast) * 255f
    val r = w.r * contrast
    val g = w.g * contrast
    val b = w.b * contrast
    return ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(r, g, b, 0f, off, r, g, b, 0f, off, r, g, b, 0f, off, 0f, 0f, 0f, 1f, 0f)))
}

private fun saturation(amount: Float) = ColorMatrix().apply { setToSaturation(amount) }

/** Contrast about [pivot] (0..1): tones above it brighten, tones below it darken. */
private fun contrast(amount: Float, pivot: Float = 0.5f): ColorMatrix {
    val off = pivot * (1f - amount) * 255f
    return ColorMatrix(floatArrayOf(amount, 0f, 0f, 0f, off, 0f, amount, 0f, 0f, off, 0f, 0f, amount, 0f, off, 0f, 0f, 0f, 1f, 0f))
}

/**
 * Selective colour: hues near the target keep their colour, everything else turns grey. The keep curve is
 * [app.viewfinder.learn.hueKeep] (tested there), gated by saturation so greys and near-whites never "match".
 */
private const val POP_SHADER = """
uniform shader image;
uniform float target;
uniform float width;
half4 main(float2 p) {
    float3 c = float3(image.eval(p).rgb);
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float h = 0.0;
    if (d > 0.0001) {
        if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
        else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
        else h = (c.r - c.g) / d + 4.0;
        h *= 60.0;
    }
    float s = mx > 0.0 ? d / mx : 0.0;
    float raw = mod(abs(h - target), 360.0);
    float dist = raw > 180.0 ? 360.0 - raw : raw;
    float keep = clamp((2.0 * width - dist) / width, 0.0, 1.0) * smoothstep(0.25, 0.45, s);
    float l = dot(c, float3(0.299, 0.587, 0.114));
    return half4(mix(float3(l), c, keep), 1.0);
}
"""

@Composable
private fun PopLayer(photo: androidx.compose.ui.graphics.ImageBitmap?, targetDeg: Float, widthDeg: Float) {
    if (photo == null) return
    val shader = remember { RuntimeShader(POP_SHADER) }
    Image(
        photo, null,
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shader.setFloatUniform("target", targetDeg)
                shader.setFloatUniform("width", widthDeg)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
            },
        contentScale = ContentScale.Crop,
    )
}
