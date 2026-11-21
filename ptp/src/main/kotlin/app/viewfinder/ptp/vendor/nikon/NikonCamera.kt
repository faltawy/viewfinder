package app.viewfinder.ptp.vendor.nikon

import app.viewfinder.ptp.DeviceInfo
import app.viewfinder.ptp.Ev
import app.viewfinder.ptp.Op
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.PropertyDesc
import app.viewfinder.ptp.PtpEvent
import app.viewfinder.ptp.PtpException
import app.viewfinder.ptp.PtpLog
import app.viewfinder.ptp.PtpReader
import app.viewfinder.ptp.PtpResponseException
import app.viewfinder.ptp.PtpTransport
import app.viewfinder.ptp.Rc
import app.viewfinder.ptp.camera.CameraDriver
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.camera.LiveFrame
import app.viewfinder.ptp.camera.LiveView
import app.viewfinder.ptp.camera.PropCategory
import app.viewfinder.ptp.camera.PropMeta
import app.viewfinder.ptp.camera.PtpCamera
import app.viewfinder.ptp.camera.StandardProps
import app.viewfinder.ptp.camera.defaultFormat
import app.viewfinder.ptp.camera.enumFormat
import app.viewfinder.ptp.camera.seconds
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Nikon's PTP vendor extension. Codes and semantics as documented by libgphoto2. */
object NikonOp {
    const val InitiateCaptureRecInSdram = 0x90C0
    const val AfDrive = 0x90C1
    const val ChangeCameraMode = 0x90C2
    const val GetLargeThumb = 0x90C4
    const val GetEvent = 0x90C7
    const val DeviceReady = 0x90C8
    const val GetVendorPropCodes = 0x90CA
    const val StartLiveView = 0x9201
    const val EndLiveView = 0x9202
    const val GetLiveViewImg = 0x9203
    const val MfDrive = 0x9204
    const val ChangeAfArea = 0x9205
    const val AfDriveCancel = 0x9206
    const val InitiateCaptureRecInMedia = 0x9207
    const val StartMovieRecInCard = 0x920A
    const val EndMovieRec = 0x920B
    const val TerminateCapture = 0x920C

    init {
        Op.registerNames(NikonCatalog.operationNames.mapValues { "Nikon.${it.value.replace(" ", "")}" })
        Rc.registerNames(NikonCatalog.responseNames.mapValues { "Nikon.${it.value.replace(" ", "")}" })
    }
}

object NikonRc {
    const val OutOfFocus = 0xA002
    const val InvalidStatus = 0xA004
    const val NotLiveView = 0xA00B
    const val MfDriveStepEnd = 0xA00C
    const val MfDriveStepInsufficiency = 0xA00E
}

/** Nikon properties the app formats itself; every other code is named from [NikonCatalog]. */
object NikonProps {
    const val ISOAuto = 0xD054
    const val ActiveDLighting = 0xD14E
    const val ExposureTime = 0xD100
    const val ACPower = 0xD101
    const val RecordingMedia = 0xD10B
    const val LiveViewStatus = 0xD1A2
    const val LiveViewImageZoomRatio = 0xD1A3
    const val LiveViewProhibitCondition = 0xD1A4
    const val FocalLengthMin = 0xD0E3
    const val FocalLengthMax = 0xD0E4
    const val MaxApAtMinFocalLength = 0xD0E5
    const val MaxApAtMaxFocalLength = 0xD0E6
    const val CameraOrientation = 0xD10E

    /** Nikon packs shutter speed as numerator << 16 | denominator; a few values are special. */
    fun shutter(v: PropValue): String {
        val raw = v.asLong ?: return defaultFormat(v)
        return when (raw) {
            0xFFFFFFFFL -> "Bulb"
            0xFFFFFFFEL -> "Time"
            0xFFFFFFFDL -> "x200"
            else -> {
                val num = raw ushr 16
                val den = raw and 0xFFFF
                when {
                    den == 0L -> "0x%X".format(raw)
                    num == 1L && den > 1 -> "1/$den"
                    else -> seconds(num.toDouble() / den)
                }
            }
        }
    }

    val quality = mapOf(
        0L to "JPEG Basic", 1L to "JPEG Normal", 2L to "JPEG Fine", 3L to "JPEG Extra fine",
        4L to "RAW", 5L to "RAW + Basic", 6L to "RAW + Normal", 7L to "RAW + Fine",
    )

    /** Live view refusal reasons, bit by bit (from libgphoto2). */
    private val prohibit = listOf(
        8 to "the battery is too low", 17 to "the camera is too hot", 9 to "of a TTL error",
        22 to "the mirror is up", 24 to "the lens is retracting", 5 to "of a minimum-aperture warning (set the lens ring to its smallest aperture)",
        15 to "the camera is still processing a shot", 2 to "of a sequence error", 31 to "the mode dial isn't on P, A, S or M",
        21 to "of a bulb warning", 20 to "the card isn't formatted", 19 to "of a card error", 18 to "the card is locked",
        14 to "there's no usable card", 12 to "a shot is waiting in the camera's buffer", 4 to "the shutter button is pressed",
    )

    fun prohibitReason(bits: Long): String? =
        prohibit.firstOrNull { (bit, _) -> bits and (1L shl bit) != 0L }?.second
            ?: if (bits != 0L) "of camera condition 0x%08X".format(bits) else null

    val curated: Map<Int, PropMeta> = mapOf(
        ExposureTime to PropMeta("Shutter speed", PropCategory.Exposure, deckOrder = 1, format = ::shutter),
        ISOAuto to PropMeta("Auto ISO", PropCategory.Exposure, deckOrder = 10, format = enumFormat(mapOf(0L to "Off", 1L to "On"))),
        ACPower to PropMeta("AC power", PropCategory.Camera, format = enumFormat(mapOf(0L to "Battery", 1L to "AC adapter"))),
        RecordingMedia to PropMeta("Save remote shots to", PropCategory.Shooting, format = enumFormat(mapOf(0L to "Memory card", 1L to "Camera buffer only"))),
        LiveViewStatus to PropMeta("Live view", PropCategory.Camera, format = enumFormat(mapOf(0L to "Off", 1L to "On"))),
        LiveViewImageZoomRatio to PropMeta("Live view zoom", PropCategory.Camera),
        LiveViewProhibitCondition to PropMeta("Live view blockers", PropCategory.Camera) { v ->
            v.asLong?.let { bits -> prohibitReason(bits)?.let { "Blocked because $it" } ?: "None" } ?: defaultFormat(v)
        },
        // Older DSLRs (D90 through the D5x00/D7x00 line) count Active D-Lighting from "Extra high" down.
        ActiveDLighting to PropMeta(
            "Active D-Lighting", PropCategory.Image, deckOrder = 11,
            format = enumFormat(mapOf(0L to "Extra high", 1L to "High", 2L to "Normal", 3L to "Low", 4L to "Off", 5L to "Auto")),
        ),
        StandardProps.CompressionSetting to PropMeta("Image quality", PropCategory.Image, deckOrder = 8, format = enumFormat(quality)),
        FocalLengthMin to PropMeta("Lens shortest focal length", PropCategory.Camera, format = StandardProps::focalLength),
        FocalLengthMax to PropMeta("Lens longest focal length", PropCategory.Camera, format = StandardProps::focalLength),
        MaxApAtMinFocalLength to PropMeta("Widest aperture, zoomed out", PropCategory.Camera, format = StandardProps::fNumber),
        MaxApAtMaxFocalLength to PropMeta("Widest aperture, zoomed in", PropCategory.Camera, format = StandardProps::fNumber),
        CameraOrientation to PropMeta(
            "Camera orientation", PropCategory.Camera,
            format = enumFormat(mapOf(0L to "Level", 1L to "Rotated 90° left", 2L to "Rotated 90° right", 3L to "Upside down")),
        ),
    )

    /** Rough grouping by name, so 100+ vendor settings land in sensible sections. */
    fun categoryFor(name: String): PropCategory {
        val n = name.lowercase()
        return when {
            listOf("iso", "exposure", "shutter", "aperture", "f-number", "fnumber", "meter", "bracket", "ev step", "program", "bulb").any { it in n } -> PropCategory.Exposure
            listOf("focus", "af-", "af ", "af area", "autofocus", "mf ").any { it in n } || n.startsWith("af") -> PropCategory.Focus
            listOf("white balance", "picture", "pic ctrl", "color", "colour", "sharp", "contrast", "saturation", "hue", "image", "jpeg", "raw", "noise", "d-lighting", "vignette", "distortion", "hdr", "quality", "space").any { it in n } -> PropCategory.Image
            listOf("flash", "timer", "release", "burst", "continuous", "interval", "movie", "video", "record", "shooting", "remote", "mirror", "delay").any { it in n } -> PropCategory.Shooting
            listOf("lens", "battery", "clock", "beep", "monitor", "display", "lcd", "language", "auto off", "power", "orientation", "copyright", "artist", "bank", "live view", "liveview", "shots", "card", "warning", "status").any { it in n } -> PropCategory.Camera
            else -> PropCategory.Other
        }
    }
}

object NikonDriver : CameraDriver {
    const val VENDOR_ID = 0x0000000AL

    override val name = "Nikon"

    // Some bodies (the D5600 among them) report Microsoft's MTP extension ID instead of Nikon's.
    override fun matches(info: DeviceInfo): Boolean =
        info.vendorExtensionId == VENDOR_ID || info.manufacturer.contains("Nikon", ignoreCase = true)

    override fun create(transport: PtpTransport, info: DeviceInfo, scope: CoroutineScope, log: PtpLog): PtpCamera =
        NikonCamera(transport, info, scope, log)
}

/**
 * Nikon bodies queue their events instead of pushing them; [NikonOp.GetEvent] drains the queue.
 * They list extra vendor properties through [NikonOp.GetVendorPropCodes], offer a larger preview
 * than the standard thumbnail, and stream live view as JPEG frames behind a small header.
 */
class NikonCamera(
    transport: PtpTransport,
    deviceInfo: DeviceInfo,
    scope: CoroutineScope,
    log: PtpLog,
) : PtpCamera(transport, deviceInfo, scope, log) {

    init {
        NikonOp // registers the vendor operation and response names for the log
    }

    override val driverName = "Nikon"

    private var vendorCodes: List<Int> = emptyList()

    /** The capture command that worked last time, so the next shot goes straight to it. */
    private var captureRoute: CaptureRoute? = null

    override val propertyCodes: List<Int>
        get() = (deviceInfo.properties + vendorCodes).distinct().sorted()

    /** Hand-made formats first, then the standard table, then libgphoto2's name for anything else. */
    override fun vendorMeta(code: Int): PropMeta? =
        NikonProps.curated[code]
            ?: if (StandardProps.meta.containsKey(code)) null
            else NikonCatalog.propNames[code]?.let { PropMeta(it, NikonProps.categoryFor(it)) }

    /**
     * libgphoto2 keeps several value tables for some properties (one per camera generation).
     * The one that labels the most of this camera's actual choices wins.
     */
    override fun metaFor(desc: PropertyDesc): PropMeta {
        val base = meta(desc.code)
        if (NikonProps.curated.containsKey(desc.code) || StandardProps.meta.containsKey(desc.code)) return base
        val tables = NikonCatalog.propTables[desc.code] ?: return base
        val values = (desc.choices + desc.current).mapNotNull { it.asLong }.toSet()
        val best = tables.maxByOrNull { t -> values.count { it in t } } ?: return base
        if (values.isNotEmpty() && values.none { it in best }) return base
        return base.copy(format = enumFormat(best))
    }

    override fun buildCapabilities(): Set<Capability> = super.buildCapabilities() + buildSet {
        if (supports(NikonOp.GetLargeThumb)) add(Capability.LargePreview)
        if (supports(NikonOp.GetEvent)) add(Capability.NewPhotoEvents)
        if (supports(NikonOp.StartLiveView) && supports(NikonOp.GetLiveViewImg)) add(Capability.LiveView)
        if (supports(NikonOp.AfDrive)) add(Capability.Autofocus)
        if (supports(NikonOp.ChangeAfArea)) add(Capability.TouchFocus)
        if (supports(NikonOp.MfDrive)) add(Capability.ManualFocus)
        if (supports(NikonOp.StartMovieRecInCard) && supports(NikonOp.EndMovieRec)) add(Capability.Movie)
        if (supports(NikonOp.TerminateCapture) && supports(NikonOp.InitiateCaptureRecInMedia)) add(Capability.Bulb)
        if (supports(NikonOp.InitiateCaptureRecInMedia) || supports(NikonOp.InitiateCaptureRecInSdram)) add(Capability.RemoteCapture)
    }

    override suspend fun onSessionOpened() {
        if (supports(NikonOp.GetVendorPropCodes)) {
            vendorCodes = runCatching { PtpReader(callIn(NikonOp.GetVendorPropCodes)).u16Array() }
                .onFailure { log.log("vendor property list unavailable: ${it.message}") }
                .getOrDefault(emptyList())
            log.log("${deviceInfo.properties.size} standard + ${vendorCodes.size} Nikon properties")
        }
        if (supports(NikonOp.GetEvent)) {
            scope.launch {
                while (isActive && transport.connected.value) {
                    runCatching { pollEvents() }
                    delay(EVENT_POLL_MS)
                }
            }
        }
    }

    /** GetEvent data: u16 count, then count × (u16 event code, u32 parameter). */
    private suspend fun pollEvents() {
        val r = PtpReader(callIn(NikonOp.GetEvent))
        repeat(r.u16()) {
            val event = PtpEvent(r.u16(), listOf(r.u32()))
            if (event.code != Ev.DevicePropChanged) {
                log.log("⚡ ${NikonCatalog.eventNames[event.code] ?: "event"} ${"0x%04X".format(event.code)}")
            }
            mapEvent(event)?.let { driverEvents.emit(it) }
        }
    }

    override suspend fun preview(handle: Long): ByteArray =
        if (supports(NikonOp.GetLargeThumb)) {
            runCatching { callIn(NikonOp.GetLargeThumb, handle) }.getOrElse { thumbnail(handle) }
        } else {
            thumbnail(handle)
        }

    override suspend fun capture() {
        shoot()
    }

    private enum class CaptureRoute { InMedia, Standard, Sdram }

    /**
     * Nikon offers three capture commands and bodies differ in which they accept in which state,
     * so they are tried in turn, starting with whichever worked last.
     */
    private suspend fun shoot() {
        val routes = buildList {
            if (supports(NikonOp.InitiateCaptureRecInMedia)) add(CaptureRoute.InMedia)
            if (supports(Op.InitiateCapture)) add(CaptureRoute.Standard)
            if (supports(NikonOp.InitiateCaptureRecInSdram)) add(CaptureRoute.Sdram)
        }.sortedBy { if (it == captureRoute) 0 else 1 }
        if (routes.isEmpty()) throw PtpResponseException(Op.InitiateCapture, Rc.OperationNotSupported)
        var last: PtpResponseException? = null
        for (route in routes) {
            val response = when (route) {
                CaptureRoute.InMedia -> transport.transaction(NikonOp.InitiateCaptureRecInMedia, listOf(NO_AF, CARD))
                CaptureRoute.Standard -> transport.transaction(Op.InitiateCapture, listOf(0, 0))
                CaptureRoute.Sdram -> transport.transaction(NikonOp.InitiateCaptureRecInSdram, listOf(NO_AF))
            }
            if (response.isOk) {
                if (captureRoute != route) log.log("capture works through $route")
                captureRoute = route
                waitUntilReady()
                return
            }
            last = PtpResponseException(
                when (route) {
                    CaptureRoute.InMedia -> NikonOp.InitiateCaptureRecInMedia
                    CaptureRoute.Standard -> Op.InitiateCapture
                    CaptureRoute.Sdram -> NikonOp.InitiateCaptureRecInSdram
                },
                response.code,
            )
            if (response.code == NikonRc.OutOfFocus) break // a focus problem, not a wrong command
        }
        throw last!!
    }

    /** Nikon answers DeviceBusy to DeviceReady while it is still working. Returns the final code. */
    private suspend fun waitUntilReady(attempts: Int = READY_ATTEMPTS): Int {
        if (!supports(NikonOp.DeviceReady)) return Rc.OK
        var rc = Rc.DeviceBusy
        repeat(attempts) {
            rc = transport.transaction(NikonOp.DeviceReady).code
            if (rc != Rc.DeviceBusy) return rc
            delay(READY_POLL_MS)
        }
        return rc
    }

    private suspend fun prohibitReason(): String? =
        if (NikonProps.LiveViewProhibitCondition in propertyCodes) {
            runCatching { PtpReader(callIn(Op.GetDevicePropValue, NikonProps.LiveViewProhibitCondition.toLong())).u32() }
                .getOrNull()?.let { NikonProps.prohibitReason(it) }
        } else {
            null
        }

    override val liveView: LiveView? =
        if (supports(NikonOp.StartLiveView) && supports(NikonOp.GetLiveViewImg)) NikonLiveView() else null

    private inner class NikonLiveView : LiveView {
        /** Size of the focus-area coordinate space, read from the frame header when present. */
        private var areaWidth = 0
        private var areaHeight = 0

        /** The camera refuses live view while it is busy (menus, playback, writing); wait and retry. */
        override suspend fun start() {
            repeat(START_ATTEMPTS) { attempt ->
                val rc = transport.transaction(NikonOp.StartLiveView).code
                if (rc == Rc.OK || rc == Rc.DeviceBusy) {
                    val ready = waitUntilReady()
                    if (ready == Rc.OK) return
                    if (attempt == START_ATTEMPTS - 1) throw PtpResponseException(NikonOp.StartLiveView, ready)
                } else {
                    prohibitReason()?.let { throw PtpException("Live view is blocked because $it") }
                    if (attempt == START_ATTEMPTS - 1) throw PtpResponseException(NikonOp.StartLiveView, rc)
                }
                delay(START_RETRY_MS)
            }
        }

        override suspend fun frame(): LiveFrame {
            val data = callIn(NikonOp.GetLiveViewImg)
            val soi = findJpegStart(data) ?: throw PtpException("Live view frame had no JPEG image")
            if (soi >= 8 && areaWidth == 0) {
                // Big-endian u16 pairs; on the bodies documented so far bytes 4..7 are the full area size.
                val w = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                val h = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                if (w in 320..12000 && h in 200..9000) {
                    areaWidth = w
                    areaHeight = h
                    log.log("live view focus area ${w}×$h, header $soi bytes")
                }
            }
            return LiveFrame(data.copyOfRange(soi, data.size))
        }

        override suspend fun stop() {
            runCatching { transport.transaction(NikonOp.EndLiveView) }
        }

        override suspend fun autofocus(): Boolean {
            val rc = transport.transaction(NikonOp.AfDrive).code
            if (rc != Rc.OK && rc != Rc.DeviceBusy) throw PtpResponseException(NikonOp.AfDrive, rc)
            return waitUntilReady() == Rc.OK
        }

        override suspend fun cancelAutofocus() {
            if (supports(NikonOp.AfDriveCancel)) runCatching { transport.transaction(NikonOp.AfDriveCancel) }
        }

        override suspend fun focusAt(x: Float, y: Float) {
            val w = if (areaWidth > 0) areaWidth else DEFAULT_AREA_WIDTH
            val h = if (areaHeight > 0) areaHeight else DEFAULT_AREA_HEIGHT
            call(NikonOp.ChangeAfArea, (x.coerceIn(0f, 1f) * w).toLong(), (y.coerceIn(0f, 1f) * h).toLong())
        }

        override suspend fun capture() {
            shoot()
        }

        /** MfDrive(direction, steps): 1 moves towards the near end, 2 towards infinity. */
        override suspend fun manualFocus(steps: Int) {
            val direction = if (steps < 0) 1L else 2L
            val amount = abs(steps).coerceAtLeast(1).toLong()
            val rc = transport.transaction(NikonOp.MfDrive, listOf(direction, amount)).code
            if (rc != Rc.OK && rc != Rc.DeviceBusy) throw PtpResponseException(NikonOp.MfDrive, rc)
            // StepEnd: the lens was already at the end. StepInsufficiency: it reached the end part-way
            // through the move (seen on the D5600 at infinity). Either way, focus is at its limit.
            when (waitUntilReady()) {
                NikonRc.MfDriveStepEnd, NikonRc.MfDriveStepInsufficiency ->
                    throw PtpException(if (direction == 1L) "Focus is at its nearest point" else "Focus is at infinity")
            }
        }

        override suspend fun startMovie() {
            call(NikonOp.StartMovieRecInCard)
        }

        override suspend fun stopMovie() {
            call(NikonOp.EndMovieRec)
            waitUntilReady()
        }

        override suspend fun startBulb() {
            if (supports(NikonOp.ChangeCameraMode)) runCatching { transport.transaction(NikonOp.ChangeCameraMode, listOf(1)) }
            call(NikonOp.InitiateCaptureRecInMedia, NO_AF, CARD)
        }

        override suspend fun endBulb() {
            call(NikonOp.TerminateCapture, 0, 0)
            waitUntilReady(attempts = 300)
        }
    }

    private companion object {
        const val EVENT_POLL_MS = 1_000L
        const val READY_POLL_MS = 100L
        const val READY_ATTEMPTS = 100
        const val START_ATTEMPTS = 4
        const val START_RETRY_MS = 700L

        /** InitiateCaptureRecInMedia flags: 0xFFFFFFFF = don't autofocus first (0xFFFFFFFE does). */
        const val NO_AF = 0xFFFFFFFFL
        const val CARD = 0L
        const val DEFAULT_AREA_WIDTH = 640
        const val DEFAULT_AREA_HEIGHT = 424

        fun findJpegStart(data: ByteArray): Int? {
            for (i in 0 until data.size - 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) return i
            }
            return null
        }
    }
}
