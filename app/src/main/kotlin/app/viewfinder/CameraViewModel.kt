package app.viewfinder

import app.viewfinder.ptp.PtpException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import java.net.ConnectException
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.viewfinder.ptp.ObjectInfo
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.PtpLog
import app.viewfinder.ptp.PtpResponseException
import app.viewfinder.ptp.Rc
import app.viewfinder.ptp.camera.Camera
import app.viewfinder.ptp.camera.CameraDrivers
import app.viewfinder.ptp.camera.CameraEvent
import app.viewfinder.ptp.camera.CameraInfo
import app.viewfinder.ptp.camera.CameraProperty
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.camera.Diagnostics
import app.viewfinder.ptp.ptpip.PtpIpTransport
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaKind { Jpeg, Raw, Video, Other }

enum class Filter(val label: String) { All("All"), Jpeg("JPEG"), Raw("RAW"), Video("Video") }

data class PhotoItem(
    val handle: Long,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val captured: LocalDateTime?,
    val width: Long,
    val height: Long,
    val saved: Boolean,
    /** The RAW twin of a JPEG shot in RAW+JPEG mode; shown as one photo. */
    val raw: PhotoItem? = null,
) {
    /** Every file behind this tile: the JPEG and, when shot RAW+JPEG, its RAW. */
    val handles: List<Long> get() = listOfNotNull(handle, raw?.handle)
    val allSaved: Boolean get() = saved && (raw?.saved ?: true)
}

/** A share in preparation. [handles] are the files it's waiting on, for per-file progress. */
data class Sharing(val done: Int, val total: Int, val handles: Set<Long>)

/** Connection steps, shown as a progress timeline. */
enum class Stage(val label: String) { Network("Camera Wi-Fi"), Camera("Camera"), Session("Session") }

sealed interface Connection {
    data object Idle : Connection
    data class Connecting(val stage: Stage, val detail: String) : Connection
    data class Connected(val model: String, val driver: String) : Connection
    data class Failed(val message: String) : Connection
}

data class LiveState(
    val running: Boolean = false,
    val frame: ImageBitmap? = null,
    val focusing: Boolean = false,
    /** Fractional focus point to draw, with a counter so repeated taps re-animate. */
    val focusPoint: Pair<Float, Float>? = null,
    val focusTap: Int = 0,
    val focused: Boolean? = null,
    val shooting: Boolean = false,
    val shots: Int = 0,
    val recording: Boolean = false,
    /** When the bulb exposure started (elapsed-realtime ms), while one is open. */
    val bulbSince: Long? = null,
    val focusingManually: Boolean = false,
    val error: String? = null,
)

data class UiState(
    val connection: Connection = Connection.Idle,
    val wifiName: String = "",
    val wifiPassword: String = "",
    val cameraAddress: String = "",
    val info: CameraInfo? = null,
    val capabilities: Set<Capability> = emptySet(),
    val photos: List<PhotoItem> = emptyList(),
    /** (read, total) while the card is being read. */
    val listing: Pair<Int, Int>? = null,
    val refreshing: Boolean = false,
    val filter: Filter = Filter.All,
    val selection: Set<Long> = emptySet(),
    /** Handle → fraction done, for files being saved. */
    val saving: Map<Long, Float> = emptyMap(),
    val queued: Set<Long> = emptySet(),
    val autoSave: Boolean = false,
    val battery: Int? = null,
    val shotsLeft: Long? = null,
    val freeBytes: Long? = null,
    val properties: Map<Int, CameraProperty> = emptyMap(),
    val loadingProperties: Boolean = false,
    val live: LiveState = LiveState(),
    val nearby: List<NearbyWifi> = emptyList(),
    val scanning: Boolean = false,
    /** False until the first Wi-Fi scan finishes, so the list shows placeholders rather than "nothing found". */
    val scanned: Boolean = false,
    /** The camera this phone connected to last, if any. */
    val lastCamera: String? = null,
    val message: String? = null,
    val shareOptions: ShareOptions = ShareOptions(),
    val heifSupported: Boolean = false,
    val lastShareApp: ShareApp? = null,
    /** A share being prepared: files fetched and converted so far. */
    val sharing: Sharing? = null,
) {
    /** RAW+JPEG pairs collapse into one tile under All and JPEG; the RAW filter lists RAW files alone. */
    val visiblePhotos: List<PhotoItem>
        get() = when (filter) {
            Filter.All -> paired.filter { it.kind != MediaKind.Other }
            Filter.Jpeg -> paired.filter { it.kind == MediaKind.Jpeg }
            Filter.Raw -> photos.filter { it.kind == MediaKind.Raw }
            Filter.Video -> photos.filter { it.kind == MediaKind.Video }
        }

    private val paired: List<PhotoItem>
        get() {
            val rawByStem = photos.filter { it.kind == MediaKind.Raw }.associateBy { it.name.substringBeforeLast('.') }
            val pairedRaws = mutableSetOf<Long>()
            val jpegs = photos.map { p ->
                if (p.kind != MediaKind.Jpeg) return@map p
                val twin = rawByStem[p.name.substringBeforeLast('.')] ?: return@map p
                pairedRaws += twin.handle
                p.copy(raw = twin)
            }
            return jpegs.filter { it.handle !in pairedRaws }
        }

    /** The gallery tile a file belongs to (its RAW+JPEG pair when there is one), whatever the filter. */
    fun tileFor(handle: Long): PhotoItem? =
        paired.firstOrNull { it.handle == handle || it.raw?.handle == handle } ?: photos.firstOrNull { it.handle == handle }

    val unsavedCount: Int get() = visiblePhotos.count { !it.allSaved }
    val busyCount: Int get() = saving.size + queued.size
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val network = CameraNetwork(app)
    private val scanner = WifiScanner(app)

    private val _state = MutableStateFlow(
        UiState(
            wifiName = settings.wifiName,
            wifiPassword = settings.wifiPassword,
            cameraAddress = settings.cameraAddress,
            autoSave = settings.autoSave,
            lastCamera = settings.lastCamera,
            shareOptions = settings.shareOptions,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val ptpLog = PtpLog { line ->
        Log.d("Viewfinder", line)
        val stamped = "${LocalTime.now().format(timeFormat)}  $line"
        _log.update { (it + stamped).takeLast(LOG_LINES) }
    }

    private var camera: Camera? = null
    private val sessionJobs = mutableListOf<Job>()
    private var liveJob: Job? = null
    private val saveQueue = Channel<Long>(Channel.UNLIMITED)
    /** Files the user asked to save (Save buttons, auto-save), as opposed to fetched only for a share. */
    private val wantedSaves = mutableSetOf<Long>()
    private val sharer = Sharer(app)
    private var shareJob: Job? = null
    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    /** Share sheets (or a direct send to the last-used app) for the activity to start. */
    val shareIntents: Flow<Intent> = _shareIntents.receiveAsFlow()
    private val thumbs = LruCache<Long, ImageBitmap>(600)
    private val previews = LruCache<Long, ImageBitmap>(12)
    private val fullImages = LruCache<String, ImageBitmap>(3)

    val diagnostics: Diagnostics? get() = camera?.diagnostics

    init {
        viewModelScope.launch { for (handle in saveQueue) saveNow(handle) }
        viewModelScope.launch(Dispatchers.Default) {
            sharer.cleanUp()
            _state.update { it.copy(heifSupported = sharer.heifSupported) }
        }
        // Opening the app reconnects to the last camera by itself, before the first frame is drawn,
        // so the form never flashes up first. Once per launch: Cancel leaves the form up.
        reconnect()
    }

    // region Connection

    fun updateWifi(name: String, password: String, address: String) {
        // Picking another network brings back the password used for it last time.
        val pass = if (name != _state.value.wifiName && password == _state.value.wifiPassword) settings.passwordFor(name) ?: "" else password
        _state.update { it.copy(wifiName = name, wifiPassword = pass, cameraAddress = address) }
    }

    /** Lists nearby Wi-Fi networks, cameras first. Needs the nearby-devices permission already granted. */
    fun scanWifi() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val found = runCatching { scanner.scan() }.getOrDefault(emptyList())
            _state.update { it.copy(nearby = found, scanning = false, scanned = true) }
        }
    }

    /**
     * Reconnects to the camera used last time, when there is one and nothing is connecting yet. Joining a
     * named network needs the nearby-devices permission already granted; without it the form stays up.
     */
    fun reconnect() {
        val s = _state.value
        if (s.lastCamera == null || s.connection !is Connection.Idle) return
        if (s.wifiName.isNotBlank() && !canJoinWifi()) return
        connect()
    }

    private fun canJoinWifi(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
    }

    fun connect() {
        val s = _state.value
        if (s.connection is Connection.Connecting || s.connection is Connection.Connected) return
        settings.wifiName = s.wifiName
        settings.wifiPassword = s.wifiPassword
        if (s.wifiName.isNotBlank()) settings.rememberPassword(s.wifiName, s.wifiPassword)
        settings.cameraAddress = s.cameraAddress
        sessionJobs += viewModelScope.launch {
            var host: String? = null
            try {
                step(Stage.Network, if (s.wifiName.isBlank()) "Looking for the camera's Wi-Fi" else "Joining ${s.wifiName}")
                val wifi = network.acquire(s.wifiName, s.wifiPassword)
                if (wifi == null) {
                    if (s.wifiName.isNotBlank()) throw IllegalStateException("Couldn't join ${s.wifiName}. Check the name and password shown on the camera.")
                    ptpLog.log("no Wi-Fi network found; trying the default network")
                }
                host = s.cameraAddress.trim().ifEmpty { wifi?.let(network::cameraAddress) ?: DEFAULT_CAMERA_ADDRESS }
                step(Stage.Camera, "Reaching $host. If the camera asks, confirm on its screen.")
                val transport = PtpIpTransport.connect(
                    host = host,
                    guid = settings.guid,
                    clientName = "Viewfinder (${Build.MODEL})",
                    socketFactory = wifi?.socketFactory ?: SocketFactory.getDefault(),
                    log = ptpLog,
                )
                step(Stage.Session, "Opening a session")
                val cam = CameraDrivers.open(transport, ptpLog)
                camera = cam
                settings.lastCamera = cam.info.model
                _state.update {
                    it.copy(
                        connection = Connection.Connected(cam.info.model, cam.info.driver),
                        info = cam.info,
                        capabilities = cam.capabilities,
                        photos = emptyList(),
                        lastCamera = cam.info.model,
                        // Placeholders until the card is read, never a flash of "the card is empty".
                        listing = 0 to 0,
                    )
                }
                watch(cam)
                refreshStatus()
                loadLibrary(cam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ptpLog.log("✕ ${e.message}")
                teardown()
                _state.update { it.copy(connection = Connection.Failed(failureMessage(e, host))) }
            }
        }
    }

    /** Plain words for the person holding the camera; the exact error stays in the connection log. */
    private fun failureMessage(e: Exception, host: String?): String {
        val where = host?.let { " at $it" }.orEmpty()
        return when (e) {
            is ConnectException, is NoRouteToHostException ->
                "The camera didn't answer$where. Check that its Wi-Fi connection is on and the phone is on the camera's network."
            is SocketTimeoutException -> "The camera stopped answering$where. Move closer and try again."
            // The PTP layer and the Wi-Fi step already word their errors for people.
            is PtpException, is IllegalStateException -> e.message ?: "Couldn't connect."
            else -> "Couldn't connect$where. The connection log has the details."
        }
    }

    fun disconnect() {
        teardown()
        _state.update {
            it.copy(
                connection = Connection.Idle, info = null, capabilities = emptySet(), photos = emptyList(),
                selection = emptySet(), saving = emptyMap(), queued = emptySet(), properties = emptyMap(), live = LiveState(),
            )
        }
    }

    private fun teardown() {
        liveJob?.cancel()
        liveJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        camera?.let { cam -> viewModelScope.launch(Dispatchers.IO) { runCatching { cam.close() } } }
        camera = null
        network.release()
        thumbs.evictAll()
        previews.evictAll()
    }

    private fun step(stage: Stage, detail: String) = _state.update { it.copy(connection = Connection.Connecting(stage, detail)) }

    private fun watch(cam: Camera) {
        sessionJobs += viewModelScope.launch {
            cam.connected.first { !it }
            if (camera === cam) {
                teardown()
                _state.update {
                    it.copy(
                        connection = Connection.Failed("The camera disconnected. It may have turned off its Wi-Fi to save power."),
                        live = LiveState(),
                    )
                }
            }
        }
        sessionJobs += viewModelScope.launch {
            cam.events.collect { event ->
                when (event) {
                    is CameraEvent.ObjectAdded -> onObjectAdded(cam, event.handle)
                    is CameraEvent.ObjectRemoved -> _state.update { s -> s.copy(photos = s.photos.filterNot { it.handle == event.handle }) }
                    CameraEvent.StorageChanged, CameraEvent.CaptureComplete -> refreshStatus()
                    is CameraEvent.PropertyChanged -> onPropertyChanged(event.code)
                }
            }
        }
    }

    // endregion

    // region Library

    private suspend fun loadLibrary(cam: Camera) {
        val handles = cam.objectHandles().reversed() // newest first
        _state.update { it.copy(listing = 0 to handles.size) }
        val batch = mutableListOf<PhotoItem>()
        handles.forEachIndexed { i, handle ->
            runCatching { cam.objectInfo(handle) }
                .onSuccess { info -> toItem(cam, info)?.let(batch::add) }
                .onFailure { ptpLog.log("skipping object ${"0x%08X".format(handle)}: ${it.message}") }
            if (batch.size >= 24 || i == handles.lastIndex) {
                val chunk = batch.toList()
                batch.clear()
                _state.update { it.copy(photos = it.photos + chunk, listing = (i + 1) to handles.size) }
            }
        }
        _state.update { it.copy(listing = null) }
    }

    fun refresh() {
        val cam = camera ?: return
        if (_state.value.listing != null) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching {
                val known = _state.value.photos.associateBy { it.handle }
                val handles = cam.objectHandles().reversed()
                val items = handles.mapNotNull { h -> known[h] ?: runCatching { toItem(cam, cam.objectInfo(h)) }.getOrNull() }
                _state.update { it.copy(photos = items) }
                refreshStatus()
            }.onFailure { e -> _state.update { it.copy(message = "Couldn't refresh: ${e.message}") } }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun onObjectAdded(cam: Camera, handle: Long) {
        if (_state.value.photos.any { it.handle == handle }) return
        val item = runCatching { cam.objectInfo(handle) }.getOrNull()?.let { toItem(cam, it) } ?: return
        _state.update { it.copy(photos = listOf(item) + it.photos, live = it.live.copy(shots = it.live.shots + 1)) }
        refreshStatus()
        if (_state.value.autoSave) enqueue(listOf(item.handle))
    }

    private fun toItem(cam: Camera, info: ObjectInfo): PhotoItem? {
        if (info.isFolder) return null
        val kind = kindOf(info.filename)
        if (kind == MediaKind.Other) return null
        val captured = info.captureDate ?: info.modificationDate
        return PhotoItem(
            handle = info.handle,
            name = info.filename,
            kind = kind,
            size = info.compressedSize,
            captured = captured,
            width = info.imageWidth,
            height = info.imageHeight,
            saved = settings.isSaved(savedKey(cam, info.filename, info.compressedSize, captured)),
        )
    }

    private suspend fun refreshStatus() {
        val cam = camera ?: return
        val battery = runCatching { cam.batteryLevel() }.getOrNull()
        val storages = runCatching { cam.storages() }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                battery = battery,
                shotsLeft = storages.mapNotNull { s -> s.freeImages }.sum().takeIf { n -> n > 0 },
                freeBytes = storages.sumOf { s -> s.freeBytes }.takeIf { n -> n > 0 },
            )
        }
    }

    suspend fun thumbnail(handle: Long): ImageBitmap? {
        thumbs.get(handle)?.let { return it }
        val cam = camera ?: return null
        if (Capability.Thumbnails !in cam.capabilities) return null
        val bytes = runCatching { cam.thumbnail(handle) }.getOrNull() ?: return null
        return decode(bytes)?.also { thumbs.put(handle, it) }
    }

    suspend fun preview(handle: Long): ImageBitmap? {
        previews.get(handle)?.let { return it }
        val cam = camera ?: return null
        val bytes = runCatching { cam.preview(handle) }.getOrNull() ?: return null
        return decode(bytes)?.also { previews.put(handle, it) }
    }

    /**
     * The saved copy on the phone, decoded at about [maxSide] pixels on its long edge: sharp when
     * zoomed, without holding a 24 MP bitmap. Only for saved JPEG/HEIF; null when the copy is gone
     * (deleted from the gallery) or unreadable, so the caller falls back to the camera's preview.
     */
    suspend fun fullImage(item: PhotoItem, maxSide: Int): ImageBitmap? {
        if (!item.saved || item.kind != MediaKind.Jpeg) return null
        val cam = camera ?: return null
        val uri = settings.savedUri(savedKey(cam, item.name, item.size, item.captured)) ?: return null
        val cacheKey = "$uri@$maxSide"
        fullImages.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val long = maxOf(info.size.width, info.size.height)
                    if (long > maxSide) {
                        val scale = maxSide.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }.asImageBitmap().also { fullImages.put(cacheKey, it) }
            } catch (e: FileNotFoundException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: IOException) {
                null
            }
        }
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    fun setFilter(filter: Filter) = _state.update { it.copy(filter = filter, selection = emptySet()) }

    fun setAutoSave(on: Boolean) {
        settings.autoSave = on
        _state.update { it.copy(autoSave = on, message = if (on) "New shots will be saved automatically" else null) }
    }

    fun toggleSelection(handle: Long) = _state.update {
        it.copy(selection = if (handle in it.selection) it.selection - handle else it.selection + handle)
    }

    fun selectAll() = _state.update { it.copy(selection = it.visiblePhotos.map { p -> p.handle }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun saveSelection() {
        val s = _state.value
        enqueue(s.visiblePhotos.filter { it.handle in s.selection }.flatMap { it.handles })
        clearSelection()
    }

    fun saveAllUnsaved() {
        val s = _state.value
        enqueue(s.visiblePhotos.flatMap { p -> listOfNotNull(p.takeIf { !it.saved }?.handle, p.raw?.takeIf { !it.saved }?.handle) })
    }

    /** Saves a tile: both files of a RAW+JPEG pair. */
    fun save(item: PhotoItem) = enqueue(item.handles)

    fun deleteSelection() {
        val cam = camera ?: return
        val s = _state.value
        val handles = s.visiblePhotos.filter { it.handle in s.selection }.flatMap { it.handles }
        clearSelection()
        deleteHandles(cam, handles)
    }

    fun delete(item: PhotoItem) {
        deleteHandles(camera ?: return, item.handles)
    }

    private fun deleteHandles(cam: Camera, handles: List<Long>) {
        viewModelScope.launch {
            var failed = 0
            handles.forEach { h ->
                runCatching { cam.delete(h) }
                    .onSuccess { _state.update { s -> s.copy(photos = s.photos.filterNot { it.handle == h }) } }
                    .onFailure { failed++ }
            }
            val done = handles.size - failed
            _state.update {
                it.copy(message = if (failed == 0) "Deleted $done ${if (done == 1) "file" else "files"} from the card" else "Couldn't delete $failed of ${handles.size} files")
            }
            refreshStatus()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun notify(message: String) = _state.update { it.copy(message = message) }

    fun capture() {
        val cam = camera ?: return
        viewModelScope.launch {
            runCatching { cam.capture() }
                .onFailure { e -> _state.update { it.copy(message = "Couldn't take a picture: ${e.message}") } }
        }
    }

    private fun enqueue(handles: List<Long>, wanted: Boolean = true) {
        if (wanted) wantedSaves += handles
        val s = _state.value
        val fresh = handles.distinct().filter { it !in s.queued && it !in s.saving }
        if (fresh.isEmpty()) return
        _state.update { it.copy(queued = it.queued + fresh) }
        fresh.forEach { saveQueue.trySend(it) }
    }

    private suspend fun saveNow(handle: Long) {
        if (handle !in _state.value.queued) return   // dropped while waiting (a cancelled share)
        val cam = camera
        val item = _state.value.photos.firstOrNull { it.handle == handle }
        if (cam == null || item == null) {
            _state.update { it.copy(queued = it.queued - handle) }
            return
        }
        _state.update { it.copy(queued = it.queued - handle, saving = it.saving + (handle to 0f)) }
        try {
            var lastShown = 0f
            val uri = MediaSaver.save(getApplication(), item.name) { out ->
                cam.download(handle, item.size, out) { done, total ->
                    val f = if (total > 0) done.toFloat() / total else 0f
                    if (f - lastShown >= 0.01f || f >= 1f) {
                        lastShown = f
                        _state.update { it.copy(saving = it.saving + (handle to f)) }
                    }
                }
            }
            settings.markSaved(savedKey(cam, item.name, item.size, item.captured), uri)
            wantedSaves -= handle
            _state.update { s ->
                val last = s.saving.size == 1 && s.queued.isEmpty()
                s.copy(
                    saving = s.saving - handle,
                    photos = s.photos.map { if (it.handle == handle) it.copy(saved = true) else it },
                    message = if (last) "Saved to ${MediaSaver.folderFor(MediaSaver.mimeFor(item.name))}" else s.message,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ptpLog.log("✕ saving ${item.name}: ${e.message}")
            _state.update { it.copy(saving = it.saving - handle, message = "Couldn't save ${item.name}: ${e.message}") }
        }
    }

    // endregion

    // region Sharing

    fun updateShareOptions(options: ShareOptions) {
        settings.shareOptions = options
        _state.update { it.copy(shareOptions = options) }
    }

    /** Reloads the last-used app (the share sheet reports it in the background). */
    fun refreshShareApp() = _state.update { it.copy(lastShareApp = sharer.lastApp(settings.lastShareApp)) }

    fun shareSelection() {
        val s = _state.value
        share(s.visiblePhotos.filter { it.handle in s.selection }, clearSelectionAfter = true)
    }

    /**
     * Shares [tiles] with [options] (the saved defaults unless given): fetches what isn't on the phone yet
     * through the save queue, makes the copies the format and privacy need, then sends the share sheet, or
     * the files straight to [target]. [focusX]/[focusY] place a single photo's crop.
     */
    fun share(
        tiles: List<PhotoItem>,
        options: ShareOptions = _state.value.shareOptions,
        target: ComponentName? = null,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        clearSelectionAfter: Boolean = false,
    ) {
        val cam = camera ?: return
        if (tiles.isEmpty() || shareJob?.isActive == true) return
        val format = options.formatFor(tiles.size)
        val plan = planShare(tiles, format)
        if (plan.files.isEmpty()) {
            _state.update { it.copy(message = "Nothing to share: RAW files can only be shared as originals") }
            return
        }
        val handles = plan.files.map { it.item.handle }
        shareJob = viewModelScope.launch {
            val fetching = mutableSetOf<Long>()
            _state.update { it.copy(sharing = Sharing(0, handles.size, handles.toSet())) }
            try {
                sharer.cleanUp()
                var outgoing = mutableListOf<Outgoing>()
                var located = 0
                if (format == ShareFormat.ContactSheet) {
                    val entries = plan.files.mapIndexed { i, f ->
                        val bytes = runCatching { cam.preview(f.item.handle) }.getOrNull() ?: runCatching { cam.thumbnail(f.item.handle) }.getOrNull()
                        progressShare(i + 1)
                        SheetEntry(f.item.name, sharer.sheetCaption(bytes, captureLabel(f.item.captured)), bytes)
                    }
                    outgoing += sharer.contactSheet(entries, shareTitle(cam), "${photos(entries.size)} · ${LocalDate.now().format(dayFormat)}")
                } else {
                    // Queue every missing file at once, so the next downloads while this one is converted.
                    val missing = plan.files.filter { localUri(cam, it.item) == null }.map { it.item.handle }
                    fetching += missing.filter { it !in _state.value.queued && it !in _state.value.saving }
                    enqueue(missing, wanted = false)
                    plan.files.forEachIndexed { i, f ->
                        val uri = awaitLocal(cam, f.item)
                        val out = if (f.render) {
                            sharer.render(uri, f.item.name, options, format, focusX, focusY)
                        } else {
                            sharer.original(uri, f.item.name, options)
                        }
                        if (out == null) located++ else outgoing += out
                        progressShare(i + 1)
                    }
                    if (format == ShareFormat.Zip && outgoing.isNotEmpty()) outgoing = mutableListOf(sharer.zip(outgoing, shareTitle(cam)))
                }
                val notes = listOfNotNull(
                    plan.skippedRaw.takeIf { it > 0 }?.let { "${files(it)} RAW skipped: they can only be shared as originals" },
                    located.takeIf { it > 0 }?.let { "${files(it)} skipped: their location can't be removed. Turn off Remove location to send them" },
                ).joinToString("\n").ifEmpty { null }
                if (outgoing.isNotEmpty()) {
                    val send = sharer.sendIntent(outgoing)
                    _shareIntents.send(if (target != null) Intent(send).setComponent(target) else sharer.chooser(send, "Share ${photos(tiles.size)}"))
                    if (clearSelectionAfter) clearSelection()
                }
                _state.update { it.copy(message = notes ?: it.message) }
            } catch (e: CancellationException) {
                // Files only this share asked for, still waiting, are dropped; a download under way finishes.
                val drop = fetching - wantedSaves
                _state.update { it.copy(queued = it.queued - drop) }
                throw e
            } catch (e: Exception) {
                ptpLog.log("✕ sharing: ${e.message}")
                _state.update { it.copy(message = "Couldn't share: ${e.message}") }
            } finally {
                _state.update { it.copy(sharing = null) }
            }
        }
    }

    fun cancelShare() {
        shareJob?.cancel()
    }

    /**
     * The photo card's caption for [item]: from the saved copy's details when it's on the phone, else from the
     * camera's preview, else just the camera model.
     */
    suspend fun shareCaption(item: PhotoItem): String {
        val cam = camera ?: return ""
        val model = cam.info.model.removePrefix("NIKON ").trim()
        return withContext(Dispatchers.IO) {
            localUri(cam, item)?.let { sharer.captionOf(it) }?.takeIf { it.isNotEmpty() }
                ?: runCatching { cam.preview(item.handle) }.getOrNull()?.let { sharer.sheetCaption(it, "") }?.takeIf { it.isNotEmpty() }?.let { "$model · $it" }
                ?: model
        }
    }

    private fun progressShare(done: Int) = _state.update { s -> s.copy(sharing = s.sharing?.copy(done = done)) }

    /** The phone's copy of [item], if it's saved and the file is still there. */
    private fun localUri(cam: Camera, item: PhotoItem): Uri? =
        settings.savedUri(savedKey(cam, item.name, item.size, item.captured))?.takeIf { uri ->
            runCatching { getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        }

    private suspend fun awaitLocal(cam: Camera, item: PhotoItem): Uri {
        localUri(cam, item)?.let { return it }
        if (item.handle !in _state.value.queued && item.handle !in _state.value.saving) enqueue(listOf(item.handle), wanted = false)
        _state.first { item.handle !in it.queued && item.handle !in it.saving }
        return localUri(cam, item) ?: throw IOException("couldn't fetch ${item.name} from the camera")
    }

    private fun shareTitle(cam: Camera): String =
        "${cam.info.model.removePrefix("NIKON ").trim().ifEmpty { "Viewfinder" }} ${LocalDate.now().format(dayFormat)}"

    private fun captureLabel(captured: LocalDateTime?): String = captured?.format(captureFormat).orEmpty()

    // endregion

    // region Settings

    fun loadProperties() {
        val cam = camera ?: return
        if (Capability.Settings !in cam.capabilities) return
        viewModelScope.launch {
            _state.update { it.copy(loadingProperties = true) }
            val props = runCatching { cam.properties() }.getOrDefault(emptyList())
            _state.update { it.copy(properties = props.associateBy { p -> p.code }, loadingProperties = false) }
        }
    }

    private suspend fun onPropertyChanged(code: Int) {
        if (_state.value.properties.isEmpty()) return
        val cam = camera ?: return
        runCatching { cam.property(code) }.getOrNull()?.let { p ->
            _state.update { it.copy(properties = it.properties + (code to p)) }
        }
    }

    fun setProperty(code: Int, value: PropValue) {
        val cam = camera ?: return
        val before = _state.value.properties[code] ?: return
        // Show the new value at once; put the old one back if the camera refuses.
        _state.update { it.copy(properties = it.properties + (code to before.copy(desc = before.desc.copy(current = value)))) }
        viewModelScope.launch {
            runCatching { cam.setProperty(code, value) }
                .onSuccess { cam.property(code)?.let { p -> _state.update { it.copy(properties = it.properties + (code to p)) } } }
                .onFailure { e ->
                    val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                    _state.update { it.copy(properties = it.properties + (code to before), message = "${before.name}: $why") }
                }
        }
    }

    fun syncClock() {
        val cam = camera ?: return
        viewModelScope.launch {
            runCatching { cam.syncClock(LocalDateTime.now()) }
                .onSuccess {
                    onPropertyChanged(app.viewfinder.ptp.Prop.DateTime)
                    _state.update { it.copy(message = "Camera clock set to this phone's time") }
                }
                .onFailure { e -> _state.update { it.copy(message = "Couldn't set the clock: ${e.message}") } }
        }
    }

    // endregion

    // region Live view

    fun startLiveView() {
        val cam = camera ?: return
        val lv = cam.liveView ?: return
        if (liveJob?.isActive == true) return
        if (_state.value.properties.isEmpty()) loadProperties()
        liveJob = viewModelScope.launch {
            _state.update { it.copy(live = LiveState(running = true)) }
            try {
                lv.start()
                var misses = 0
                while (isActive) {
                    val frame = runCatching { lv.frame() }
                    frame.onSuccess { f ->
                        misses = 0
                        decode(f.jpeg)?.let { bmp -> _state.update { s -> s.copy(live = s.live.copy(frame = bmp, error = null)) } }
                    }.onFailure { e ->
                        if (++misses >= 20) throw e
                        delay(150)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                ptpLog.log("✕ live view: $why")
                _state.update { it.copy(live = it.live.copy(running = false, error = "Live view stopped: $why")) }
            }
        }
    }

    fun stopLiveView() {
        val cam = camera
        liveJob?.cancel()
        liveJob = null
        _state.update { it.copy(live = LiveState()) }
        cam?.liveView?.let { lv -> viewModelScope.launch { runCatching { lv.stop() } } }
    }

    fun autofocus() {
        val lv = camera?.liveView ?: return
        viewModelScope.launch {
            _state.update { it.copy(live = it.live.copy(focusing = true, focused = null)) }
            val ok = runCatching { lv.autofocus() }.getOrDefault(false)
            _state.update { it.copy(live = it.live.copy(focusing = false, focused = ok)) }
        }
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val lv = cam.liveView ?: return
        _state.update { it.copy(live = it.live.copy(focusPoint = x to y, focusTap = it.live.focusTap + 1, focused = null)) }
        viewModelScope.launch {
            if (Capability.TouchFocus in cam.capabilities) runCatching { lv.focusAt(x, y) }
            if (Capability.Autofocus in cam.capabilities) autofocus()
        }
    }

    /** Nudges the focus motor; negative is nearer. */
    fun manualFocus(steps: Int) {
        val lv = camera?.liveView ?: return
        if (_state.value.live.focusingManually) return
        viewModelScope.launch {
            _state.update { it.copy(live = it.live.copy(focusingManually = true)) }
            runCatching { lv.manualFocus(steps) }.onFailure { e -> _state.update { it.copy(message = e.message) } }
            _state.update { it.copy(live = it.live.copy(focusingManually = false)) }
        }
    }

    fun toggleMovie() {
        val lv = camera?.liveView ?: return
        val recording = _state.value.live.recording
        viewModelScope.launch {
            runCatching { if (recording) lv.stopMovie() else lv.startMovie() }
                .onSuccess { _state.update { it.copy(live = it.live.copy(recording = !recording)) } }
                .onFailure { e ->
                    val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                    _state.update { it.copy(message = "Couldn't ${if (recording) "stop" else "start"} recording: $why") }
                }
        }
    }

    /** First press opens the shutter, second closes it. Only while the camera is set to Bulb. */
    fun toggleBulb() {
        val lv = camera?.liveView ?: return
        val open = _state.value.live.bulbSince != null
        viewModelScope.launch {
            runCatching { if (open) lv.endBulb() else lv.startBulb() }
                .onSuccess { _state.update { it.copy(live = it.live.copy(bulbSince = if (open) null else android.os.SystemClock.elapsedRealtime())) } }
                .onFailure { e ->
                    val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                    _state.update { it.copy(live = it.live.copy(bulbSince = null), message = "Bulb exposure failed: $why") }
                }
        }
    }

    /** Erases every card. The screen asks for confirmation first. */
    fun formatCards() {
        val cam = camera ?: return
        viewModelScope.launch {
            runCatching {
                val cards = cam.storages()
                cards.forEach { cam.formatCard(it.id) }
                cards.size
            }.onSuccess { n ->
                _state.update { it.copy(photos = emptyList(), selection = emptySet(), message = if (n == 1) "Card formatted" else "$n cards formatted") }
                refreshStatus()
            }.onFailure { e ->
                val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                _state.update { it.copy(message = "Couldn't format: $why") }
            }
        }
    }

    fun shoot() {
        val cam = camera ?: return
        val lv = cam.liveView
        viewModelScope.launch {
            _state.update { it.copy(live = it.live.copy(shooting = true)) }
            runCatching { if (lv != null && _state.value.live.running) lv.capture() else cam.capture() }
                .onFailure { e ->
                    val why = (e as? PtpResponseException)?.let { r -> refusal(r.responseCode) } ?: e.message
                    _state.update { it.copy(message = "Couldn't take a picture: $why") }
                }
            _state.update { it.copy(live = it.live.copy(shooting = false)) }
        }
    }

    // endregion

    override fun onCleared() {
        teardown()
    }

    private companion object {
        const val DEFAULT_CAMERA_ADDRESS = "192.168.1.1"
        const val LOG_LINES = 800
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        val captureFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

        fun files(n: Int) = if (n == 1) "1 file" else "$n files"

        fun kindOf(name: String): MediaKind = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "heif", "hif", "heic" -> MediaKind.Jpeg
            "nef", "nrw", "cr2", "cr3", "arw", "raf", "dng", "orf", "rw2" -> MediaKind.Raw
            "mov", "mp4" -> MediaKind.Video
            else -> MediaKind.Other
        }

        /** Handles change between sessions, so saved files are remembered by camera, name, size and time. */
        fun savedKey(cam: Camera, name: String, size: Long, captured: LocalDateTime?) =
            "${cam.info.serialNumber}/$name/$size/$captured"

        /** Plain-language reasons for the refusals people actually hit. */
        fun refusal(code: Int): String = when (code) {
            Rc.InvalidParameter, Rc.ParameterNotSupported -> "the camera doesn't accept that value right now"
            0x200F -> "locked by the camera's current mode"
            Rc.DeviceBusy -> "the camera is busy, try again"
            Rc.OperationNotSupported -> "not available over Wi-Fi on this camera"
            0xA002 -> "couldn't focus"
            0xA004 -> "not possible in the camera's current state"
            0xA005 -> "can't be changed in this mode"
            0xA00B -> "live view isn't running"
            else -> Rc.name(code)
        }
    }
}
