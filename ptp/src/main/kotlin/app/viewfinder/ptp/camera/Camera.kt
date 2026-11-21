package app.viewfinder.ptp.camera

import app.viewfinder.ptp.ObjectInfo
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.PropertyDesc
import java.io.OutputStream
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the app sees of a camera, whatever its brand or connection. */
interface Camera {
    val info: CameraInfo
    val capabilities: Set<Capability>
    val connected: StateFlow<Boolean>
    val events: Flow<CameraEvent>

    suspend fun storages(): List<StorageSummary>

    /** Every object on every card, folders included; newest is usually last. */
    suspend fun objectHandles(): List<Long>

    suspend fun objectInfo(handle: Long): ObjectInfo

    /** A small JPEG thumbnail (about 160 px wide on most cameras). */
    suspend fun thumbnail(handle: Long): ByteArray

    /** The best quick preview the camera offers; falls back to [thumbnail]. */
    suspend fun preview(handle: Long): ByteArray

    /** Streams the full original file to [out]. [onProgress] gets (bytes done, total). */
    suspend fun download(handle: Long, size: Long, out: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> })

    /** Needs [Capability.Delete]. */
    suspend fun delete(handle: Long)

    /** Battery percentage, when the camera reports one. */
    suspend fun batteryLevel(): Int?

    /** Takes a picture onto the card. Needs [Capability.RemoteCapture]. */
    suspend fun capture()

    /** Every device property the camera exposes, with the app's name and formatting for it. */
    suspend fun properties(): List<CameraProperty>

    suspend fun property(code: Int): CameraProperty?

    /** Needs the property to be writable; the value must be one of its choices when it has any. */
    suspend fun setProperty(code: Int, value: PropValue)

    /** Sets the camera's clock. Needs [Capability.ClockSync]. */
    suspend fun syncClock(now: LocalDateTime)

    /** Erases every file on a card. Needs [Capability.FormatCard]. */
    suspend fun formatCard(storageId: Long)

    /** Present when the camera can stream its viewfinder. */
    val liveView: LiveView?

    /** The raw lists the camera reported, for the inspector screen. */
    val diagnostics: Diagnostics

    suspend fun close()
}

data class CameraInfo(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serialNumber: String,
    /** Which driver is talking to the camera, for the log and the about screen. */
    val driver: String,
)

data class StorageSummary(
    val id: Long,
    val label: String,
    val freeBytes: Long,
    /** Null when the camera doesn't estimate it. */
    val freeImages: Long?,
)

data class CameraProperty(val desc: PropertyDesc, val meta: PropMeta) {
    val code: Int get() = desc.code
    val name: String get() = meta.name
    val display: String get() = meta.format(desc.current)
    fun format(value: PropValue): String = meta.format(value)
    val editable: Boolean get() = desc.writable
}

data class Diagnostics(
    val vendorExtension: String,
    val operations: List<String>,
    val events: List<String>,
    val properties: List<String>,
)

enum class Capability {
    Browse,
    Thumbnails,
    LargePreview,
    ResumableDownload,
    RemoteCapture,
    BatteryLevel,
    NewPhotoEvents,
    Settings,
    ClockSync,
    Delete,
    LiveView,
    Autofocus,
    TouchFocus,
    ManualFocus,
    Movie,
    Bulb,
    FormatCard,
}

/** The camera's viewfinder streamed as JPEG frames. */
interface LiveView {
    suspend fun start()
    suspend fun frame(): LiveFrame
    suspend fun stop()

    /** Runs autofocus; true when the camera reports focus achieved. Needs [Capability.Autofocus]. */
    suspend fun autofocus(): Boolean

    /** Moves the focus point; fractions of the frame, 0..1 from the top left. Needs [Capability.TouchFocus]. */
    suspend fun focusAt(x: Float, y: Float)

    /** Takes a picture without leaving live view. */
    suspend fun capture()

    /** Stops a running autofocus. */
    suspend fun cancelAutofocus() {}

    /** Steps the focus motor; negative is towards the near end. Needs [Capability.ManualFocus]. */
    suspend fun manualFocus(steps: Int)

    /** Needs [Capability.Movie]. */
    suspend fun startMovie()

    suspend fun stopMovie()

    /** Opens the shutter until [endBulb]. Needs [Capability.Bulb] and the camera set to Bulb. */
    suspend fun startBulb()

    suspend fun endBulb()
}

class LiveFrame(val jpeg: ByteArray)

sealed interface CameraEvent {
    data class ObjectAdded(val handle: Long) : CameraEvent
    data class ObjectRemoved(val handle: Long) : CameraEvent
    data object StorageChanged : CameraEvent
    data object CaptureComplete : CameraEvent
    data class PropertyChanged(val code: Int) : CameraEvent
}
