package app.viewfinder.ptp.camera

import app.viewfinder.ptp.ByteArraySink
import app.viewfinder.ptp.DataSink
import app.viewfinder.ptp.DeviceInfo
import app.viewfinder.ptp.Ev
import app.viewfinder.ptp.ObjectInfo
import app.viewfinder.ptp.Op
import app.viewfinder.ptp.Prop
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.PropertyDesc
import app.viewfinder.ptp.PtpEvent
import app.viewfinder.ptp.encodeValue
import app.viewfinder.ptp.hex16
import app.viewfinder.ptp.hex32
import app.viewfinder.ptp.PtpLog
import app.viewfinder.ptp.PtpReader
import app.viewfinder.ptp.PtpResponse
import app.viewfinder.ptp.PtpResponseException
import app.viewfinder.ptp.PtpTransport
import app.viewfinder.ptp.Rc
import app.viewfinder.ptp.StorageInfo
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

/**
 * A camera driven with standard PTP operations only. Brand drivers subclass it and override what
 * their cameras do better or differently.
 */
open class PtpCamera(
    protected val transport: PtpTransport,
    val deviceInfo: DeviceInfo,
    /** Cancelled on [close]; drivers run background polling in it. */
    protected val scope: CoroutineScope,
    protected val log: PtpLog,
) : Camera {

    open val driverName: String = "Generic PTP"

    override val info: CameraInfo
        get() = CameraInfo(
            manufacturer = deviceInfo.manufacturer,
            model = deviceInfo.model,
            firmware = deviceInfo.deviceVersion,
            serialNumber = deviceInfo.serialNumber,
            driver = driverName,
        )

    override val capabilities: Set<Capability> by lazy { buildCapabilities() }

    protected open fun buildCapabilities(): Set<Capability> = buildSet {
        if (supports(Op.GetObjectHandles) && supports(Op.GetObjectInfo) && supports(Op.GetObject)) add(Capability.Browse)
        if (supports(Op.GetThumb)) add(Capability.Thumbnails)
        if (supports(Op.GetPartialObject)) add(Capability.ResumableDownload)
        if (supports(Op.InitiateCapture)) add(Capability.RemoteCapture)
        if (Prop.BatteryLevel in deviceInfo.properties && supports(Op.GetDevicePropValue)) add(Capability.BatteryLevel)
        if (Ev.ObjectAdded in deviceInfo.events) add(Capability.NewPhotoEvents)
        if (supports(Op.GetDevicePropDesc) && propertyCodes.isNotEmpty()) add(Capability.Settings)
        if (supports(Op.SetDevicePropValue) && Prop.DateTime in propertyCodes) add(Capability.ClockSync)
        if (supports(Op.DeleteObject)) add(Capability.Delete)
        if (supports(Op.FormatStore)) add(Capability.FormatCard)
    }

    /** Property codes to expose; drivers add vendor codes the DeviceInfo leaves out. */
    protected open val propertyCodes: List<Int> get() = deviceInfo.properties.sorted()

    /** Driver-specific names and formatting; null falls back to the standard table. */
    protected open fun vendorMeta(code: Int): PropMeta? = null

    fun meta(code: Int): PropMeta = vendorMeta(code) ?: StandardProps.meta[code] ?: unknownMeta(code)

    /** Like [meta], but may use the property's own choices to pick how to label its values. */
    protected open fun metaFor(desc: PropertyDesc): PropMeta = meta(desc.code)

    override val connected: StateFlow<Boolean> get() = transport.connected

    /** Events a driver discovers itself, for example by polling. */
    protected val driverEvents = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)

    override val events: Flow<CameraEvent> by lazy {
        merge(transport.events.mapNotNull(::mapEvent), driverEvents)
    }

    protected fun supports(op: Int): Boolean = op in deviceInfo.operations

    protected open fun mapEvent(event: PtpEvent): CameraEvent? = when (event.code) {
        Ev.ObjectAdded -> event.params.firstOrNull()?.let { CameraEvent.ObjectAdded(it) }
        Ev.ObjectRemoved -> event.params.firstOrNull()?.let { CameraEvent.ObjectRemoved(it) }
        Ev.StoreAdded, Ev.StoreRemoved, Ev.StorageInfoChanged -> CameraEvent.StorageChanged
        Ev.CaptureComplete -> CameraEvent.CaptureComplete
        Ev.DevicePropChanged -> event.params.firstOrNull()?.let { CameraEvent.PropertyChanged(it.toInt()) }
        else -> null
    }

    /** Runs right after the session opens. Drivers start polling or vendor setup here. */
    open suspend fun onSessionOpened() {}

    /** Runs an operation with no data phase and throws unless the camera answers OK. */
    protected suspend fun call(op: Int, vararg params: Long): PtpResponse =
        transport.transaction(op, params.toList()).also { if (!it.isOk) throw PtpResponseException(op, it.code) }

    /** Runs an operation that returns data and throws unless the camera answers OK. */
    protected suspend fun callIn(op: Int, vararg params: Long): ByteArray {
        val sink = ByteArraySink()
        val response = transport.transaction(op, params.toList(), dataIn = sink)
        if (!response.isOk) throw PtpResponseException(op, response.code)
        return sink.toByteArray()
    }

    override suspend fun storages(): List<StorageSummary> {
        val ids = PtpReader(callIn(Op.GetStorageIDs)).u32Array()
            .filter { it and 0xFFFF != 0L } // a zero low half means "slot without a card"
        return ids.map { id ->
            val s = StorageInfo.parse(id, callIn(Op.GetStorageInfo, id))
            StorageSummary(
                id = id,
                label = s.volumeLabel.ifEmpty { s.description },
                freeBytes = s.freeSpaceBytes,
                freeImages = s.freeSpaceImages.takeIf { it != 0xFFFFFFFFL },
            )
        }
    }

    override suspend fun objectHandles(): List<Long> =
        PtpReader(callIn(Op.GetObjectHandles, ALL_STORAGES, 0, 0)).u32Array()

    override suspend fun objectInfo(handle: Long): ObjectInfo = ObjectInfo.parse(handle, callIn(Op.GetObjectInfo, handle))

    override suspend fun thumbnail(handle: Long): ByteArray = callIn(Op.GetThumb, handle)

    override suspend fun preview(handle: Long): ByteArray = thumbnail(handle)

    override suspend fun download(handle: Long, size: Long, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        if (supports(Op.GetPartialObject) && size in 1 until 0xFFFFFFFFL) {
            // Piece by piece, so thumbnails and other calls can run between pieces.
            var offset = 0L
            while (offset < size) {
                val piece = callIn(Op.GetPartialObject, handle, offset, minOf(downloadChunkSize, size - offset))
                if (piece.isEmpty()) break
                out.write(piece)
                offset += piece.size
                onProgress(offset, size)
            }
            if (offset < size) throw PtpResponseException(Op.GetPartialObject, Rc.IncompleteTransfer)
        } else {
            var total = size
            var done = 0L
            val sink = object : DataSink {
                override fun begin(totalLength: Long) {
                    total = totalLength
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    out.write(buffer, offset, length)
                    done += length
                    onProgress(done, total)
                }
            }
            val response = transport.transaction(Op.GetObject, listOf(handle), dataIn = sink)
            if (!response.isOk) throw PtpResponseException(Op.GetObject, response.code)
        }
    }

    override suspend fun batteryLevel(): Int? {
        if (Capability.BatteryLevel !in capabilities) return null
        return runCatching { PtpReader(callIn(Op.GetDevicePropValue, Prop.BatteryLevel.toLong())).u8() }.getOrNull()
    }

    override suspend fun capture() {
        call(Op.InitiateCapture, 0, 0)
    }

    override suspend fun delete(handle: Long) {
        call(Op.DeleteObject, handle, 0)
    }

    override suspend fun properties(): List<CameraProperty> =
        propertyCodes.mapNotNull { code -> runCatching { property(code) }.getOrNull() }

    override suspend fun property(code: Int): CameraProperty? {
        if (!supports(Op.GetDevicePropDesc)) return null
        val desc = PropertyDesc.parse(callIn(Op.GetDevicePropDesc, code.toLong()))
        return CameraProperty(desc, metaFor(desc))
    }

    override suspend fun setProperty(code: Int, value: PropValue) {
        val desc = property(code)?.desc ?: throw PtpResponseException(Op.SetDevicePropValue, Rc.DevicePropNotSupported)
        val response = transport.transaction(
            Op.SetDevicePropValue,
            listOf(code.toLong()),
            dataOut = encodeValue(desc.dataType, value),
        )
        if (!response.isOk) throw PtpResponseException(Op.SetDevicePropValue, response.code)
        log.log("set ${meta(code).name} (${hex16(code)}) to ${meta(code).format(value)}")
    }

    override suspend fun syncClock(now: LocalDateTime) {
        setProperty(Prop.DateTime, PropValue.Str(now.format(clockFormat)))
    }

    override suspend fun formatCard(storageId: Long) {
        call(Op.FormatStore, storageId, 0)
    }

    override val liveView: LiveView? = null

    override val diagnostics: Diagnostics
        get() = Diagnostics(
            vendorExtension = "${hex32(deviceInfo.vendorExtensionId)} ${deviceInfo.vendorExtensionDesc}".trim(),
            operations = deviceInfo.operations.sorted().map { "${hex16(it)}  ${Op.name(it)}" },
            events = deviceInfo.events.sorted().map { hex16(it) },
            properties = propertyCodes.map { "${hex16(it)}  ${meta(it).name}" },
        )

    override suspend fun close() {
        scope.cancel()
        if (transport.connected.value) runCatching { transport.transaction(Op.CloseSession) }
        transport.close()
    }

    /** Bytes per GetPartialObject piece. */
    open val downloadChunkSize: Long = 1L shl 20

    companion object {
        const val ALL_STORAGES = 0xFFFFFFFFL
        private val clockFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    }
}
