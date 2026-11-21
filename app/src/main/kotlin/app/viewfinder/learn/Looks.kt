package app.viewfinder.learn

import kotlin.math.abs

/** What a recipe's before/after photo shows; each look is drawn from one bundled, credited [photo]. */
enum class Look(val photo: String) {
    SoftBackground("p447"),
    FreezeVsFlow("p88"),
    Warm("p65"),
    MonoRed("p1011"),
    Miniature("p88"),
    PopColour("p1011"),
    Silhouette("p399"),
    Vivid("p1043"),
}

/** The colour filters a black-and-white Picture Control can imitate. */
enum class MonoFilter { None, Yellow, Orange, Red, Green }

/** How much of each channel goes into the grey; the three always add up to one, so white stays white. */
data class MonoWeights(val r: Float, val g: Float, val b: Float)

/**
 * A filter passes its own colour and holds back its opposite: red brightens red and darkens blue sky,
 * green the reverse. No filter is plain luma (Rec. 601).
 */
fun monoWeights(filter: MonoFilter): MonoWeights = when (filter) {
    MonoFilter.None -> MonoWeights(0.299f, 0.587f, 0.114f)
    MonoFilter.Yellow -> MonoWeights(0.45f, 0.5f, 0.05f)
    MonoFilter.Orange -> MonoWeights(0.6f, 0.35f, 0.05f)
    MonoFilter.Red -> MonoWeights(0.8f, 0.2f, 0f)
    MonoFilter.Green -> MonoWeights(0.2f, 0.7f, 0.1f)
}

/**
 * How much of a colour with hue [hueDeg] survives when only hues near [targetDeg] are kept: all of it within
 * [widthDeg] (measured around the colour wheel), none beyond twice that, fading in between.
 */
fun hueKeep(hueDeg: Float, targetDeg: Float, widthDeg: Float): Float {
    val raw = abs(hueDeg - targetDeg) % 360f
    val d = if (raw > 180f) 360f - raw else raw
    return ((2f * widthDeg - d) / widthDeg).coerceIn(0f, 1f)
}
