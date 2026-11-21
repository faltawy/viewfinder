package app.viewfinder.learn

import app.viewfinder.exposureLabel
import app.viewfinder.trimNumber
import kotlin.math.abs
import kotlin.math.roundToInt

/** EXIF ExposureProgram values that matter to a beginner. */
enum class Program { Manual, Program, AperturePriority, ShutterPriority, Scene }

/** What a photo's EXIF says about how it was taken; any field may be missing. */
data class Shot(
    val fNumber: Double? = null,
    val seconds: Double? = null,
    val iso: Int? = null,
    val focalMm: Double? = null,
    val flashFired: Boolean? = null,
    val program: Program? = null,
    val camera: String? = null,
)

/** One plain-language line about a photo, and the lesson that explains it (a [Lesson.id]). */
data class Insight(val label: String, val title: String, val body: String, val lesson: String?)

/** EXIF ExposureProgram: 1 manual, 2 program, 3 aperture priority, 4 shutter priority, 5–8 the camera's scene programs. */
fun programFromExif(value: Int): Program? = when (value) {
    1 -> Program.Manual
    2 -> Program.Program
    3 -> Program.AperturePriority
    4 -> Program.ShutterPriority
    in 5..8 -> Program.Scene
    else -> null
}

/** Shutter speeds a camera offers, in thirds of a stop (as 1/x), for naming a hand-holding limit. */
private val SPEEDS = listOf(4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)

/** The D5600's sensor is APS-C (DX): a lens frames like one 1.5 times longer on full frame. */
private const val CROP = 1.5

/** Reads a photo's settings back as what they did to the picture, in the order you'd set them. */
fun explain(shot: Shot): List<Insight> = buildList {
    shot.program?.let { add(program(it)) }
    shot.fNumber?.takeIf { it > 0 }?.let { add(aperture(it)) }
    shot.seconds?.takeIf { it > 0 }?.let { add(shutter(it, shot.focalMm)) }
    shot.iso?.takeIf { it > 0 }?.let { add(iso(it)) }
    shot.focalMm?.takeIf { it > 0 }?.let { add(focal(it)) }
    if (shot.flashFired == true) add(Insight("Flash", "The flash fired", "There wasn't enough light, or the camera was told to fill in shadows, so the built-in flash lit the scene.", null))
}

private fun program(p: Program) = when (p) {
    Program.Manual -> Insight("Mode M", "You set everything", "Aperture, shutter speed and ISO were all your choice; the camera only measured the light.", "together")
    Program.Program -> Insight("Mode P", "The camera balanced it, you could steer", "The camera chose aperture and shutter speed together for a good exposure; turning the command dial would have shifted the pair.", "together")
    Program.AperturePriority -> Insight("Mode A", "You picked the aperture", "You chose how wide the lens opened; the camera picked the shutter speed to match the light.", "aperture")
    Program.ShutterPriority -> Insight("Mode S", "You picked the shutter speed", "You chose how long the shutter stayed open; the camera picked the aperture to match the light.", "shutter")
    Program.Scene -> Insight("Scene mode", "The camera chose for the scene", "A scene or auto mode picked every setting for you, tuned to what it thought you were shooting.", "meet")
}

private fun aperture(f: Double): Insight {
    val label = "f/${trimNumber(f)}"
    return when {
        f <= 2.8 -> Insight(label, "Wide open", "A big opening lets in lots of light and keeps only a thin slice sharp, so the background melts into blur.", "aperture")
        f <= 6.3 -> Insight(label, "The middle of the range", "A medium opening: your subject and a bit around it are sharp, the far background slightly soft.", "aperture")
        f <= 11 -> Insight(label, "Closed down", "A small opening keeps most of the scene sharp, from near to far, which suits landscapes and groups.", "aperture")
        else -> Insight(label, "A tiny opening", "Very little light gets in, and nearly everything is sharp; it needs bright light or a slow shutter.", "aperture")
    }
}

private fun shutter(seconds: Double, focalMm: Double?): Insight {
    val label = exposureLabel(seconds)
    if (seconds >= 1) {
        return Insight(label, "A long exposure", "The shutter stayed open for seconds: anything that moved became a smooth streak, and the camera needed to sit very still.", "shutter")
    }
    if (focalMm != null && focalMm > 0 && 1 / seconds < CROP * focalMm) {
        val limit = SPEEDS.minBy { abs(it - CROP * focalMm) }
        return Insight(label, "Slow enough to blur", "Slower than about 1/$limit s at this zoom, so the smallest wobble of your hands shows. Brace yourself, or raise the ISO for a faster shutter.", "steady")
    }
    return when {
        seconds <= 1.0 / 1000 -> Insight(label, "Freezes fast action", "So brief that even running, splashing or flying things stop dead.", "shutter")
        seconds <= 1.0 / 250 -> Insight(label, "Freezes people moving", "Fast enough that walking people and gestures come out crisp.", "shutter")
        seconds <= 1.0 / 60 -> Insight(label, "Steady hands are enough", "Quick enough to hold the camera without blur, but fast motion may smear a little.", "shutter")
        else -> Insight(label, "Slow: hold still", "Things that moved will blur; a steady grip or a surface to rest on keeps the rest sharp.", "steady")
    }
}

private fun iso(iso: Int): Insight {
    val label = "ISO $iso"
    return when {
        iso <= 200 -> Insight(label, "Clean and bright", "The sensor wasn't boosted at all: there was plenty of light, so the photo is as clean as it gets.", "iso")
        iso <= 800 -> Insight(label, "A small boost", "The sensor was turned up a little for the light; grain is hard to see.", "iso")
        iso <= 3200 -> Insight(label, "Dim light, some grain", "The sensor was turned up a lot to cope with dim light; zoom in and you'll see some grain.", "iso")
        else -> Insight(label, "A very dark scene", "The sensor was pushed hard to see in the dark, so grain and blotchy colour show up.", "iso")
    }
}

private fun focal(mm: Double): Insight {
    val label = "${mm.roundToInt()} mm"
    return when {
        mm < 24 -> Insight(label, "Wide: fits a lot in", "Zoomed all the way out, so the frame takes in a lot and stretches what's close.", null)
        mm <= 70 -> Insight(label, "A natural view", "A middle zoom that frames the world much as your eyes see it.", null)
        else -> Insight(label, "Zoomed in", "A long zoom brings far things close and flattens distance, and shows every shake.", null)
    }
}
