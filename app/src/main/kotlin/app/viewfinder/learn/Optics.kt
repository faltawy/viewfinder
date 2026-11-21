package app.viewfinder.learn

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/** Stops brighter (+) or darker (−) than a correct exposure, for a scene whose light is [sceneEv] (EV at ISO 100). */
fun exposureOffset(fNumber: Double, seconds: Double, iso: Int, sceneEv: Double): Double =
    sceneEv - (log2(fNumber * fNumber / seconds) - log2(iso / 100.0))

/** How far, in pixels, a hand turning at [radiansPerSecond] smears a photo exposed for [seconds]. */
fun shakeBlur(radiansPerSecond: Float, seconds: Float, pixelsPerRadian: Float): Float =
    radiansPerSecond * seconds * pixelsPerRadian

data class Gains(val r: Float, val g: Float, val b: Float)

/**
 * Channel gains that turn a photo balanced for [lightKelvin] into what the camera records when set to [setKelvin]:
 * the camera divides out the colour of the light it was told about, so telling it the wrong light tints the photo
 * the opposite way. Green stays 1, so brightness barely changes.
 */
fun whiteBalanceGains(setKelvin: Int, lightKelvin: Int): Gains {
    val light = blackbody(lightKelvin)
    val set = blackbody(setKelvin)
    val g = light[1] / set[1]
    return Gains(light[0] / set[0] / g, 1f, light[2] / set[2] / g)
}

/** The colour of light at a colour temperature, as 0–255 RGB (Tanner Helland's fit to the blackbody curve). */
private fun blackbody(kelvin: Int): FloatArray {
    val t = kelvin / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 1.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return floatArrayOf(r.coerceIn(1.0, 255.0).toFloat(), g.coerceIn(1.0, 255.0).toFloat(), b.coerceIn(1.0, 255.0).toFloat())
}

enum class Third(val x: Float, val y: Float) {
    TopLeft(1 / 3f, 1 / 3f), TopRight(2 / 3f, 1 / 3f), BottomLeft(1 / 3f, 2 / 3f), BottomRight(2 / 3f, 2 / 3f)
}

/** The thirds crossing a point (0..1 in the frame) sits on, if within [tolerance]. */
fun thirdAt(x: Float, y: Float, tolerance: Float = 0.05f): Third? =
    Third.entries.minBy { hypot(it.x - x, it.y - y) }.takeIf { hypot(it.x - x, it.y - y) <= tolerance }
