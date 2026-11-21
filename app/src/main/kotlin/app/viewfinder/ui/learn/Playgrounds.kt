package app.viewfinder.ui.learn

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.viewfinder.exposureLabel
import app.viewfinder.learn.Playground
import app.viewfinder.learn.Third
import app.viewfinder.learn.exposureOffset
import app.viewfinder.learn.shakeBlur
import app.viewfinder.learn.thirdAt
import app.viewfinder.learn.Gains
import app.viewfinder.learn.whiteBalanceGains
import app.viewfinder.trimNumber
import app.viewfinder.ui.Motion
import app.viewfinder.ui.Palette
import app.viewfinder.ui.QuietButton
import app.viewfinder.ui.SegmentedControl
import app.viewfinder.ui.fadingEdges
import app.viewfinder.ui.pressScale
import app.viewfinder.ui.tabular
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

@Composable
fun PlaygroundFor(playground: Playground, modifier: Modifier = Modifier) {
    when (playground) {
        Playground.Steady -> SteadyPlayground(modifier)
        Playground.Aperture -> AperturePlayground(modifier)
        Playground.Shutter -> ShutterPlayground(modifier)
        Playground.Iso -> IsoPlayground(modifier)
        Playground.Exposure -> ExposurePlayground(modifier)
        Playground.WhiteBalance -> WhiteBalancePlayground(modifier)
        Playground.Thirds -> ThirdsPlayground(modifier)
    }
}

// ——— Shared pieces ———

private val APERTURES = listOf(1.8, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
private val SHUTTERS = listOf(1 / 4000.0, 1 / 2000.0, 1 / 1000.0, 1 / 500.0, 1 / 250.0, 1 / 125.0, 1 / 60.0, 1 / 30.0, 1 / 15.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1.0)
private val ISOS = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)

/** How open the lens is at [fNumber]: 1 wide open (f/1.8), 0 stopped right down (f/22). Blur follows the opening's diameter (1/f), not the f-number. */
internal fun apertureOpen(fNumber: Double): Float = ((1 / fNumber - 1 / 22.0) / (1 / 1.8 - 1 / 22.0)).toFloat()

/** Background blur, in pixels, for a lens [open] that much on a photo [widthPx] wide. */
internal fun apertureBlur(open: Float, widthPx: Float): Float = open * open * widthPx * 0.022f

/** City traffic covers about a fifth of the frame's height in a second, seen from up where p88 was taken. */
private const val TRAFFIC_PER_SECOND = 0.2f

/** p447 at a lens [open] that much (see [apertureOpen]): the mountains blurred behind the sharp figure. */
@Composable
internal fun ApertureLayers(open: Float) {
    val plate = rememberLearnPhoto("p447_plate.jpg")
    val subject = rememberLearnPhoto("p447_subject.png")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Layer(plate, apertureBlur(open, with(LocalDensity.current) { maxWidth.toPx() }))
        Layer(subject)
    }
}

/** p88 with the shutter open for [seconds]: the cars streak along the road, the road stays sharp. */
@Composable
internal fun TrafficLayers(seconds: Float) {
    val plate = rememberLearnPhoto("p88_plate.jpg")
    val cars = rememberLearnPhoto("p88_cars.png")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        Layer(plate)
        Layer(cars, blurX = 0f, blurY = seconds * h * TRAFFIC_PER_SECOND)
    }
}

/** The camera's white balance as a colour filter: red and blue scaled against green. */
internal fun wbFilter(g: Gains): ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(g.r, 0f, 0f, 0f, 0f, 0f, g.g, 0f, 0f, 0f, 0f, 0f, g.b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

private fun fLabel(f: Double) = "f/${trimNumber(f)}"
private fun sLabel(s: Double) = exposureLabel(s).removeSuffix(" s").let { if (s >= 1) "$it s" else it }

/** The line under a playground that says what you're seeing; its height is held so swaps never jump. */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    val motion = Motion.current
    AnimatedContent(text, transitionSpec = { motion.swap() }, label = "caption", modifier = modifier.fillMaxWidth()) { t ->
        Text(t, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, minLines = 2)
    }
}

/** A layer of a photo filling its box, blurred by [blurX]/[blurY] pixels (0 = sharp). */
@Composable
internal fun Layer(bitmap: ImageBitmap?, blurX: Float = 0f, blurY: Float = blurX, colorFilter: ColorFilter? = null) {
    if (bitmap == null) return
    Image(
        bitmap, null,
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                renderEffect = if (blurX > 0.3f || blurY > 0.3f) BlurEffect(blurX.coerceAtLeast(0.01f), blurY.coerceAtLeast(0.01f), TileMode.Clamp) else null
            },
        contentScale = ContentScale.Crop,
        colorFilter = colorFilter,
    )
}

/**
 * A sensor's response, as a shader: [gain] brightens or darkens in linear light (stops = log2 gain), and grain
 * grows with [sigma] and sits mostly in the shadows, as real sensor noise does.
 */
private const val SENSOR_SHADER = """
uniform shader image;
uniform float gain;
uniform float sigma;
uniform float grain;
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }
half4 main(float2 p) {
    half4 c = image.eval(p);
    float3 lin = pow(max(float3(c.rgb), float3(0.0)), float3(2.2)) * gain;
    float3 col = pow(lin, float3(1.0 / 2.2));
    float2 q = floor(p / grain);
    float n = hash(q) + hash(q + 19.19) + hash(q + 47.11) - 1.5;
    float3 ch = float3(hash(q + 3.7), hash(q + 11.3), hash(q + 29.9)) - 0.5;
    float luma = dot(col, float3(0.299, 0.587, 0.114));
    float amount = sigma * (0.6 + 0.8 * (1.0 - smoothstep(0.0, 0.9, luma)));
    col += amount * (n * 1.2 + ch * 0.7);
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""

/** How strong sensor noise is at an ISO: it grows with the square root of the amplification. */
private fun noiseAt(iso: Int) = 0.006f * sqrt(iso / 100f)

@Composable
internal fun SensorLayer(bitmap: ImageBitmap?, gain: Float, sigma: Float, blur: Float = 0f) {
    if (bitmap == null) return
    val shader = remember { RuntimeShader(SENSOR_SHADER) }
    val grain = with(LocalDensity.current) { 0.7.dp.toPx() }.coerceAtLeast(1f)
    Image(
        bitmap, null,
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shader.setFloatUniform("gain", gain)
                shader.setFloatUniform("sigma", sigma)
                shader.setFloatUniform("grain", grain)
                val sensor = RenderEffect.createRuntimeShaderEffect(shader, "image")
                renderEffect = if (blur > 0.3f) {
                    RenderEffect.createChainEffect(sensor, RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
                } else {
                    sensor.asComposeRenderEffect()
                }
            },
        contentScale = ContentScale.Crop,
    )
}

/** A small round readout in a photo's corner (the f-number with its iris, the shutter speed). */
@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall.tabular(), color = Palette.Text)
    }
}

/** The lens's iris: six blades closing in as the f-number grows. */
@Composable
private fun Iris(open: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Palette.Text.copy(alpha = 0.9f), r, c, style = Stroke(1.5.dp.toPx()))
        val hole = r * (0.18f + 0.72f * open)
        val path = Path().apply {
            for (k in 0..5) {
                val a = Math.toRadians(k * 60.0 + 15.0)
                val p = Offset(c.x + (hole * kotlin.math.cos(a)).toFloat(), c.y + (hole * kotlin.math.sin(a)).toFloat())
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawCircle(Palette.Text.copy(alpha = 0.25f), r - 1.dp.toPx(), c)
        drawPath(path, Color.Black)
        drawPath(path, Palette.Text.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
    }
}

/** A row of choices that may scroll; the chosen one fills with the accent. */
@Composable
private fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth().fadingEdges(scroll).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val fill by animateColorAsState(if (on) Palette.Accent else Palette.Text.copy(alpha = 0.07f), Motion.current.enter(), label = "chip")
            val ink by animateColorAsState(if (on) Palette.OnAccent else Palette.TextSecondary, Motion.current.enter(), label = "chip-ink")
            val interaction = remember { MutableInteractionSource() }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                modifier = Modifier
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(50))
                    .background(fill)
                    .semantics { this.selected = on }
                    .clickable(interaction, ripple(), role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
    }
}

// ——— Aperture ———

@Composable
private fun AperturePlayground(modifier: Modifier) {
    var i by rememberSaveable { mutableIntStateOf(5) }
    val f = APERTURES[i]
    val open = apertureOpen(f)
    val amount by animateFloatAsState(open, Motion.current.move(), label = "aperture")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CreditedPhoto("p447") {
            ApertureLayers(amount)
            Badge(fLabel(f), Modifier.align(Alignment.TopStart)) { Iris(amount) }
        }
        ValueScale("Aperture", APERTURES.map(::fLabel), i, { i = it })
        Caption(
            when {
                f <= 2.8 -> "Wide open. The mountains melt into soft shapes and only he is sharp: he's what the photo is about."
                f <= 5.6 -> "The background softens, still easy to recognise. A good everyday portrait setting."
                f <= 11 -> "Most of the scene is sharp now, near and far. Landscape territory."
                else -> "A tiny opening: everything from the wall to the peaks is sharp, and much less light gets in."
            },
        )
    }
}

// ——— Shutter ———

@Composable
private fun ShutterPlayground(modifier: Modifier) {
    var i by rememberSaveable { mutableIntStateOf(2) }
    val t = SHUTTERS[i]
    val streak by animateFloatAsState(t.toFloat(), Motion.current.move(), label = "streak")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CreditedPhoto("p88") {
            TrafficLayers(streak)
            Badge(sLabel(t), Modifier.align(Alignment.TopStart))
        }
        ValueScale("Shutter speed", SHUTTERS.map(::sLabel), i, { i = it })
        Caption(
            when {
                t <= 1 / 500.0 -> "Every car frozen mid-lane, sharp enough to read the roofs."
                t <= 1 / 60.0 -> "A hint of movement creeps in. The road and the paint stay perfectly still."
                t <= 1 / 8.0 -> "Cars start to stream. Everything that doesn't move is still sharp."
                else -> "Only the road stays. The traffic has dissolved into ghosts and streaks."
            },
        )
    }
}

// ——— ISO ———

@Composable
private fun IsoPlayground(modifier: Modifier) {
    var i by rememberSaveable { mutableIntStateOf(2) }
    val iso = ISOS[i]
    val photo = rememberLearnPhoto("p122")
    // The scene was exposed right at ISO 3200 with f/4 and 1/60 s; every stop below is a stop darker.
    val gain by animateFloatAsState(iso / 3200f, Motion.current.move(), label = "iso-gain")
    val sigma by animateFloatAsState(noiseAt(iso), Motion.current.move(), label = "iso-noise")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CreditedPhoto("p122") {
            Zoomable { SensorLayer(photo, gain, sigma) }
            Badge("ISO $iso", Modifier.align(Alignment.TopStart))
            Badge("f/4 · 1/60", Modifier.align(Alignment.TopEnd))
        }
        ValueScale("ISO", ISOS.map { "$it" }, i, { i = it })
        Caption(
            when {
                iso <= 400 -> "Clean, but far too dark: there isn't enough light at night for a low ISO."
                iso <= 1600 -> "Brighter. Zoom into the sky and the first fine grain appears."
                iso <= 6400 -> "Bright and lively. Look closely at the dark water: grain and speckles of colour."
                else -> "As bright as day, but the shadows boil with grain and the detail turns mushy."
            },
        )
    }
}

/** Pinch to zoom into a photo (up to 4×), double-tap to go back. */
@Composable
private fun Zoomable(content: @Composable () -> Unit) {
    val motion = Motion.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, delta, zoom, _ ->
                    val next = (scale.value * zoom).coerceIn(1f, 4f)
                    scope.launch { scale.snapTo(next) }
                    // Keep the photo covering the frame: it can only pan as far as it has grown.
                    val lx = size.width / 2f * (next - 1f)
                    val ly = size.height / 2f * (next - 1f)
                    pan = Offset((pan.x + delta.x).coerceIn(-lx, lx), (pan.y + delta.y).coerceIn(-ly, ly))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scope.launch { scale.animateTo(1f, motion.move()) }
                    pan = Offset.Zero
                })
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = pan.x
                translationY = pan.y
            },
    ) { content() }
}

// ——— All three ———

/** The landscape's light: a bright, slightly hazy day (EV 14 at ISO 100). */
private const val SCENE_EV = 14.0

@Composable
private fun ExposurePlayground(modifier: Modifier) {
    var a by rememberSaveable { mutableIntStateOf(4) }
    var s by rememberSaveable { mutableIntStateOf(2) }
    var iso by rememberSaveable { mutableIntStateOf(0) }
    val photo = rememberLearnPhoto("p1043")
    val offset = exposureOffset(APERTURES[a], SHUTTERS[s], ISOS[iso], SCENE_EV)
    val gain by animateFloatAsState(2.0.pow(offset).toFloat(), Motion.current.move(), label = "exp-gain")
    val sigma by animateFloatAsState(noiseAt(ISOS[iso]), Motion.current.move(), label = "exp-noise")
    // Hand-held at 18 mm on a DX body, anything slower than about 1/30 s starts to shake.
    val shake = (log2(SHUTTERS[s] * 30.0).toFloat()).coerceAtLeast(0f) * 3.5f
    val blur by animateFloatAsState(shake, Motion.current.move(), label = "exp-shake")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CreditedPhoto("p1043") {
            SensorLayer(photo, gain, sigma, blur)
        }
        Meter(offset)
        ValueScale("Aperture", APERTURES.map(::fLabel), a, { a = it }, spacingDp = 58f)
        ValueScale("Shutter speed", SHUTTERS.map(::sLabel), s, { s = it }, spacingDp = 58f)
        ValueScale("ISO", ISOS.map { "$it" }, iso, { iso = it }, spacingDp = 58f)
        val stops = abs(offset)
        Caption(
            when {
                stops < 0.35 && shake > 0.5f -> "Balanced, but the shutter is slow enough that your hands would blur it. Try a wider aperture or more ISO instead."
                stops < 0.35 -> "Balanced. That's one of many right answers: try trading a stop of aperture for a stop of shutter."
                offset < 0 -> "Too dark by about ${stopsLabel(stops)}. Open the aperture, slow the shutter, or raise the ISO."
                else -> "Too bright by about ${stopsLabel(stops)}: the sky is burning out. Close the aperture, speed up the shutter, or lower the ISO."
            },
        )
    }
}

private fun stopsLabel(stops: Double): String {
    val r = (stops * 3).roundToInt() / 3.0
    return when {
        r >= 3 -> "3 stops or more"
        abs(r - 1) < 0.05 -> "1 stop"
        else -> "${trimNumber((r * 10).roundToInt() / 10.0)} stops"
    }
}

/**
 * The viewfinder's exposure meter, Nikon-style: + on the left, − on the right, bars growing from 0 in thirds of
 * a stop towards whichever side the exposure is off.
 */
@Composable
private fun Meter(offset: Double, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(offset.toFloat().coerceIn(-3.2f, 3.2f), Motion.current.move(), label = "meter")
    val balanced = abs(offset) < 0.35
    val color by animateColorAsState(if (balanced) Palette.Accent else Palette.Text, Motion.current.enter(), label = "meter-color")
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.tabular()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .semantics { this.stateDescription = if (balanced) "Balanced" else if (offset < 0) "Under" else "Over" },
    ) {
        val pad = 28.dp.toPx()
        val w = size.width - pad * 2
        val y = size.height * 0.42f
        fun xFor(stop: Float) = pad + w / 2 - stop / 3f * (w / 2)   // + on the left
        for (k in -9..9) {
            val x = xFor(k / 3f)
            val major = k % 3 == 0
            drawLine(Palette.Text.copy(alpha = if (major) 0.9f else 0.45f), Offset(x, y - (if (major) 7 else 4).dp.toPx()), Offset(x, y), 1.5.dp.toPx())
        }
        drawLine(Palette.Text.copy(alpha = 0.9f), Offset(xFor(3f), y), Offset(xFor(-3f), y), 1.dp.toPx())
        val labels = listOf("+" to 3.6f, "2" to 2f, "1" to 1f, "0" to 0f, "1" to -1f, "2" to -2f, "−" to -3.6f)
        for ((t, stop) in labels) {
            val l = measurer.measure(t, style.copy(color = Palette.TextSecondary))
            drawText(l, topLeft = Offset(xFor(stop) - l.size.width / 2f, y + 6.dp.toPx()))
        }
        // Bars: one per third of a stop between 0 and the reading.
        val thirds = (shown * 3).roundToInt()
        val dir = if (thirds > 0) 1 else -1
        for (k in 1..abs(thirds)) {
            val x = xFor(dir * k / 3f)
            drawRect(color, Offset(x - 2.dp.toPx(), y - 18.dp.toPx()), Size(4.dp.toPx(), 9.dp.toPx()))
        }
        if (thirds == 0) drawRect(color, Offset(xFor(0f) - 2.dp.toPx(), y - 18.dp.toPx()), Size(4.dp.toPx(), 9.dp.toPx()))
    }
}

// ——— White balance ———

private data class Light(val name: String, val kelvin: Int?)

/** The D5600's white balance presets (Auto reads the light itself; this photo's light was direct sun). */
private val LIGHTS = listOf(
    Light("Auto", null), Light("Incandescent", 3000), Light("Fluorescent", 4000), Light("Direct sunlight", 5200),
    Light("Flash", 5400), Light("Cloudy", 6000), Light("Shade", 8000),
)
private const val PHOTO_LIGHT = 5200

@Composable
private fun WhiteBalancePlayground(modifier: Modifier) {
    var i by rememberSaveable { mutableIntStateOf(1) }
    val set = LIGHTS[i].kelvin ?: PHOTO_LIGHT
    val g = whiteBalanceGains(set, PHOTO_LIGHT)
    val motion = Motion.current
    val r by animateFloatAsState(g.r, motion.move(), label = "wb-r")
    val b by animateFloatAsState(g.b, motion.move(), label = "wb-b")
    val matrix = wbFilter(Gains(r, 1f, b))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            LearnPhoto("p237", colorFilter = matrix) {
                Badge(if (LIGHTS[i].kelvin != null) "${LIGHTS[i].name} · ${set} K" else "Auto", Modifier.align(Alignment.TopStart))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Credit("p237") }
        }
        ChoiceChips(LIGHTS.map { it.name }, i, { i = it })
        TemperatureStrip(set)
        Caption(
            when {
                set < PHOTO_LIGHT - 300 -> "The camera thinks the light is orange, like a lamp, so it cools everything down. Out in daylight, that turns the whole photo blue."
                set > PHOTO_LIGHT + 300 -> "The camera thinks it's in blue shade, so it warms everything up. In sunlight, that goes amber."
                else -> "Right light, true colours: the floorboards look like wood again, and his black fur is black."
            },
        )
    }
}

/** Where the chosen light sits between candle-orange and shade-blue. */
@Composable
private fun TemperatureStrip(kelvin: Int, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState(((kelvin - 2500f) / (9000f - 2500f)).coerceIn(0f, 1f), Motion.current.move(), label = "kelvin")
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val h = 6.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFFFF9A3C), Color(0xFFFFE2B8), Color(0xFFF4F4F4), Color(0xFFCFE0FF), Color(0xFF8DB6FF))),
            Offset(0f, (size.height - h) / 2), Size(size.width, h), CornerRadius(h),
        )
        val x = pos * size.width
        drawCircle(Color.White, size.height / 2, Offset(x, size.height / 2))
        drawCircle(Color.Black.copy(alpha = 0.4f), size.height / 2, Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
    }
}

// ——— Thirds ———

/** Her face in the photo (fractions of width and height), read off a gridded copy. */
private val FACE = Offset(0.47f, 0.40f)

@Composable
private fun ThirdsPlayground(modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    var fx by rememberSaveable { mutableFloatStateOf(1 / 6f) }
    var fy by rememberSaveable { mutableFloatStateOf(1 / 6f) }
    var cropped by rememberSaveable { mutableStateOf(false) }
    val photo = rememberLearnPhoto("p1011")
    val frame = 2 / 3f
    // Where her face falls inside the frame.
    val inFrame = Offset((FACE.x - fx) / frame, (FACE.y - fy) / frame)
    val third = thirdAt(inFrame.x, inFrame.y, tolerance = 0.06f)
    LaunchedEffect(third) { if (third != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    val crop by animateFloatAsState(if (cropped) 1f else 0f, Motion.current.move(), label = "crop")
    val glow by animateFloatAsState(if (third != null) 1f else 0f, Motion.current.enter(), label = "third-glow")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(20.dp)).background(Palette.Surface)) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Cropping: grow the photo so the frame fills the box.
                            val s = 1f + crop * (1 / frame - 1f)
                            scaleX = s
                            scaleY = s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            translationX = -fx * w * s * crop
                            translationY = -fy * h * s * crop
                        },
                ) { Layer(photo) }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, d ->
                                change.consume()
                                if (!cropped) {
                                    fx = (fx + d.x / w).coerceIn(0f, 1 - frame)
                                    fy = (fy + d.y / h).coerceIn(0f, 1 - frame)
                                }
                            }
                        },
                ) {
                    val fade = 1f - crop
                    if (fade <= 0f) return@Canvas
                    val r = Rect(fx * w, fy * h, (fx + frame) * w, (fy + frame) * h)
                    val scrim = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, w, h))
                        addRect(r)
                    }
                    drawPath(scrim, Color.Black.copy(alpha = 0.55f * fade))
                    drawRect(Color.White.copy(alpha = fade), r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                    for (k in 1..2) {
                        val x = r.left + r.width * k / 3
                        val y = r.top + r.height * k / 3
                        drawLine(Color.White.copy(alpha = 0.55f * fade), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.55f * fade), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                    }
                    for (t in Third.entries) {
                        val p = Offset(r.left + r.width * t.x, r.top + r.height * t.y)
                        val on = t == third
                        drawCircle(Color.White.copy(alpha = 0.8f * fade), 4.dp.toPx(), p)
                        if (on) {
                            drawCircle(Palette.Accent.copy(alpha = 0.35f * glow * fade), 22.dp.toPx(), p)
                            drawCircle(Palette.Accent.copy(alpha = glow * fade), 14.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Credit("p1011") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton(if (cropped) "Back to the frame" else "See it cropped", { cropped = !cropped })
        }
        Caption(
            when (third) {
                Third.TopRight -> "That's it. She sits on a third, with open water to look and paddle into. The photo breathes."
                Third.TopLeft -> "On a third, but she's looking out of the frame now, into the edge. Try the other side."
                null -> if (abs(inFrame.x - 0.5f) < 0.08f) "Dead centre: balanced, but static. Slide the frame so she sits on one of the lines." else "Getting there. Watch for a crossing to light up."
                else -> "On a third. Try keeping her face up near the top line."
            },
        )
    }
}

// ——— Steady ———

private val STEADY_SPEEDS = listOf(1 / 250.0, 1 / 60.0, 1 / 15.0, 1 / 4.0)

@Composable
private fun SteadyPlayground(modifier: Modifier) {
    var i by rememberSaveable { mutableIntStateOf(2) }
    val t = STEADY_SPEEDS[i]
    val photo = rememberLearnPhoto("p122")
    val hands = rememberHandMotion()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CreditedPhoto("p122") {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = with(LocalDensity.current) { maxWidth.toPx() }
                // A standard zoom on a DX body sees about 0.65 radians across the frame.
                val perRad = w / 0.65f
                val bx = shakeBlur(hands.yaw, t.toFloat(), perRad)
                val by = shakeBlur(hands.pitch, t.toFloat(), perRad)
                val sx by animateFloatAsState(bx, Motion.current.press(), label = "shake-x")
                val sy by animateFloatAsState(by, Motion.current.press(), label = "shake-y")
                Layer(photo, sx, sy)
                Badge(sLabel(t), Modifier.align(Alignment.TopStart))
                Badge(handsLabel(hands), Modifier.align(Alignment.TopEnd))
            }
        }
        SegmentedControl(STEADY_SPEEDS.map(::sLabel), i, { i = it }, Modifier.fillMaxWidth())
        val blurNow = shakeBlur(maxOf(hands.yaw, hands.pitch), t.toFloat(), 1000f / 0.65f)
        Caption(
            when {
                !hands.real -> "This phone has no motion sensor, so this shows a typical pair of hands. Faster speeds stay sharp; slower ones smear."
                blurNow < 1.5f -> "Sharp. At this speed, the way you're holding it can't spoil the photo. Try a slower one."
                blurNow < 5f -> "Slightly soft. Fine on a phone screen, but zoom in and you'd notice."
                else -> "Smeared. Tuck your elbows in, breathe out, or rest the phone on something and watch it clear."
            },
        )
    }
}

private class HandMotion(val yaw: Float, val pitch: Float, val real: Boolean)

private fun handsLabel(h: HandMotion): String {
    val m = maxOf(h.yaw, h.pitch)
    return when {
        m < 0.008f -> "Rock steady"
        m < 0.03f -> "Steady"
        m < 0.08f -> "A little shaky"
        else -> "Shaky"
    }
}

/** How fast your hands are turning the phone right now (radians per second, smoothed), from the gyroscope. */
@Composable
private fun rememberHandMotion(): HandMotion {
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyro = remember { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    var yaw by remember { mutableFloatStateOf(0.03f) }
    var pitch by remember { mutableFloatStateOf(0.03f) }
    if (gyro != null) {
        DisposableEffect(gyro) {
            var y2 = 0f
            var p2 = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    // Root-mean-square over roughly the last third of a second.
                    y2 += 0.12f * (e.values[1] * e.values[1] - y2)
                    p2 += 0.12f * (e.values[0] * e.values[0] - p2)
                    yaw = sqrt(y2)
                    pitch = sqrt(p2)
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }
            sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensors.unregisterListener(listener) }
        }
    }
    return HandMotion(yaw, pitch, gyro != null)
}
