package app.viewfinder.ptp.camera

import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.hex16
import kotlin.math.abs
import kotlin.math.roundToLong

enum class PropCategory(val label: String) {
    Exposure("Exposure"),
    Focus("Focus"),
    Image("Image"),
    Shooting("Shooting"),
    Camera("Camera"),
    Other("Other"),
}

/** How the app names and shows one property. */
data class PropMeta(
    val name: String,
    val category: PropCategory,
    /** Shown on the remote's control deck, in this order. */
    val deckOrder: Int? = null,
    val format: (PropValue) -> String = ::defaultFormat,
)

fun defaultFormat(v: PropValue): String = when (v) {
    is PropValue.Num -> v.value.toString()
    is PropValue.Str -> v.value
    is PropValue.Arr -> v.values.joinToString(", ")
}

fun enumFormat(names: Map<Long, String>): (PropValue) -> String = { v ->
    v.asLong?.let { names[it] ?: "0x%X".format(it) } ?: defaultFormat(v)
}

/** Standard PTP property codes (ISO 15740 §13) with their meanings. */
object StandardProps {
    /** MTP: who opened the session, e.g. "Windows/6.0 MTPClassDriver". */
    const val SessionInitiatorInfo = 0xD406
    /** MTP: what kind of device it says it is. */
    const val PerceivedDeviceType = 0xD407
    const val BatteryLevel = 0x5001
    const val FunctionalMode = 0x5002
    const val ImageSize = 0x5003
    const val CompressionSetting = 0x5004
    const val WhiteBalance = 0x5005
    const val RGBGain = 0x5006
    const val FNumber = 0x5007
    const val FocalLength = 0x5008
    const val FocusDistance = 0x5009
    const val FocusMode = 0x500A
    const val ExposureMeteringMode = 0x500B
    const val FlashMode = 0x500C
    const val ExposureTime = 0x500D
    const val ExposureProgramMode = 0x500E
    const val ExposureIndex = 0x500F
    const val ExposureBiasCompensation = 0x5010
    const val DateTime = 0x5011
    const val CaptureDelay = 0x5012
    const val StillCaptureMode = 0x5013
    const val Contrast = 0x5014
    const val Sharpness = 0x5015
    const val DigitalZoom = 0x5016
    const val EffectMode = 0x5017
    const val BurstNumber = 0x5018
    const val BurstInterval = 0x5019
    const val TimelapseNumber = 0x501A
    const val TimelapseInterval = 0x501B
    const val FocusMeteringMode = 0x501C
    const val UploadURL = 0x501D
    const val Artist = 0x501E
    const val CopyrightInfo = 0x501F

    fun fNumber(v: PropValue) = v.asLong?.let { "f/" + trim(it / 100.0) } ?: defaultFormat(v)

    /** Hundredths of a millimetre. */
    fun focalLength(v: PropValue) = v.asLong?.let { trim(it / 100.0) + " mm" } ?: defaultFormat(v)

    /** Standard exposure time is in units of 0.1 ms. */
    fun exposureTime(v: PropValue) = v.asLong?.let { seconds(it / 10_000.0) } ?: defaultFormat(v)

    fun ev(v: PropValue) = v.asLong?.let {
        val ev = it / 1000.0
        if (abs(ev) < 0.05) "±0" else (if (ev > 0) "+" else "−") + trim(abs(ev), 1)
    } ?: defaultFormat(v)

    fun iso(v: PropValue) = v.asLong?.let { if (it == 0xFFFFL) "Auto" else "ISO $it" } ?: defaultFormat(v)

    val whiteBalance = mapOf(
        1L to "Manual", 2L to "Auto", 3L to "One-push auto", 4L to "Daylight", 5L to "Fluorescent",
        6L to "Incandescent", 7L to "Flash", 0x8010L to "Cloudy", 0x8011L to "Shade", 0x8012L to "Kelvin",
        0x8013L to "Preset", 0x8014L to "Off", 0x8016L to "Natural light auto",
    )
    val focusMode = mapOf(1L to "Manual", 2L to "Auto", 3L to "Macro", 0x8010L to "AF-S", 0x8011L to "AF-C", 0x8012L to "AF-A", 0x8013L to "MF")
    val metering = mapOf(1L to "Average", 2L to "Center-weighted", 3L to "Matrix", 4L to "Spot", 0x8010L to "Highlight-weighted")
    val flashMode = mapOf(1L to "Auto", 2L to "Off", 3L to "Fill", 4L to "Red-eye auto", 5L to "Red-eye fill", 6L to "External sync", 0x8010L to "Fill", 0x8011L to "Rear curtain", 0x8012L to "Slow sync", 0x8013L to "Red-eye slow sync")
    val programMode = mapOf(
        1L to "M", 2L to "P", 3L to "A", 4L to "S", 5L to "Creative", 6L to "Action", 7L to "Portrait",
        0x8010L to "Auto", 0x8011L to "Portrait", 0x8012L to "Landscape", 0x8013L to "Close-up", 0x8014L to "Sports",
        0x8015L to "Night portrait", 0x8016L to "Flash off", 0x8017L to "Child", 0x8018L to "Scene", 0x8019L to "Effects",
    )
    val stillCapture = mapOf(
        1L to "Single", 2L to "Continuous H", 3L to "Time-lapse", 0x8010L to "Continuous L", 0x8011L to "Self-timer",
        0x8012L to "Mirror up", 0x8013L to "Remote", 0x8014L to "Quick remote", 0x8015L to "Delayed remote", 0x8016L to "Quiet",
    )
    val focusMetering = mapOf(1L to "Center spot", 2L to "Multi spot", 0x8010L to "Single point", 0x8011L to "Dynamic area", 0x8012L to "Auto area", 0x8013L to "3D tracking")

    val meta: Map<Int, PropMeta> = mapOf(
        SessionInitiatorInfo to PropMeta("Session opened by", PropCategory.Camera),
        PerceivedDeviceType to PropMeta(
            "Device type", PropCategory.Camera,
            format = enumFormat(mapOf(0L to "Generic", 1L to "Still camera", 2L to "Media player", 3L to "Mobile phone", 4L to "Video player", 5L to "Personal assistant", 6L to "Audio recorder")),
        ),
        BatteryLevel to PropMeta("Battery", PropCategory.Camera) { v -> v.asLong?.let { "$it%" } ?: defaultFormat(v) },
        ImageSize to PropMeta("Image size", PropCategory.Image),
        CompressionSetting to PropMeta("Image quality", PropCategory.Image, deckOrder = 8),
        WhiteBalance to PropMeta("White balance", PropCategory.Image, deckOrder = 5, format = enumFormat(whiteBalance)),
        FNumber to PropMeta("Aperture", PropCategory.Exposure, deckOrder = 2, format = ::fNumber),
        FocalLength to PropMeta("Focal length", PropCategory.Camera, format = ::focalLength),
        FocusDistance to PropMeta("Focus distance", PropCategory.Focus),
        FocusMode to PropMeta("Focus mode", PropCategory.Focus, deckOrder = 6, format = enumFormat(focusMode)),
        ExposureMeteringMode to PropMeta("Metering", PropCategory.Exposure, deckOrder = 7, format = enumFormat(metering)),
        FlashMode to PropMeta("Flash", PropCategory.Shooting, format = enumFormat(flashMode)),
        ExposureTime to PropMeta("Shutter speed", PropCategory.Exposure, deckOrder = 1, format = ::exposureTime),
        ExposureProgramMode to PropMeta("Mode", PropCategory.Exposure, deckOrder = 0, format = enumFormat(programMode)),
        ExposureIndex to PropMeta("ISO", PropCategory.Exposure, deckOrder = 3, format = ::iso),
        ExposureBiasCompensation to PropMeta("Exposure compensation", PropCategory.Exposure, deckOrder = 4, format = ::ev),
        DateTime to PropMeta("Camera clock", PropCategory.Camera, format = ::formatPtpDateTime),
        CaptureDelay to PropMeta("Self-timer delay", PropCategory.Shooting) { v -> v.asLong?.let { trim(it / 1000.0) + " s" } ?: defaultFormat(v) },
        StillCaptureMode to PropMeta("Release mode", PropCategory.Shooting, deckOrder = 9, format = enumFormat(stillCapture)),
        Contrast to PropMeta("Contrast", PropCategory.Image),
        Sharpness to PropMeta("Sharpness", PropCategory.Image),
        DigitalZoom to PropMeta("Digital zoom", PropCategory.Image),
        EffectMode to PropMeta("Effect", PropCategory.Image),
        BurstNumber to PropMeta("Burst count", PropCategory.Shooting),
        BurstInterval to PropMeta("Burst interval", PropCategory.Shooting),
        TimelapseNumber to PropMeta("Time-lapse shots", PropCategory.Shooting),
        TimelapseInterval to PropMeta("Time-lapse interval", PropCategory.Shooting),
        FocusMeteringMode to PropMeta("AF-area mode", PropCategory.Focus, format = enumFormat(focusMetering)),
        Artist to PropMeta("Artist", PropCategory.Camera),
        CopyrightInfo to PropMeta("Copyright", PropCategory.Camera),
    )
}

/** Fallback for codes nobody has named yet: still listed, with the raw code. */
fun unknownMeta(code: Int) = PropMeta("Property ${hex16(code)}", PropCategory.Other)

fun seconds(s: Double): String = when {
    s <= 0 -> "0"
    s < 0.3 -> "1/" + (1 / s).roundToLong()
    s < 1 -> trim(s, 1) + "\""
    else -> trim(s, 1) + "\""
}

fun trim(v: Double, decimals: Int = 1): String {
    val s = "%.${decimals}f".format(v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** "20261121T143005" → "2026-11-21 14:30:05". */
fun formatPtpDateTime(v: PropValue): String {
    val s = (v as? PropValue.Str)?.value ?: return defaultFormat(v)
    if (s.length < 15) return s
    return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(9, 11)}:${s.substring(11, 13)}:${s.substring(13, 15)}"
}
