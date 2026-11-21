package app.viewfinder.ptp

import app.viewfinder.ptp.ptpip.PtpIpPackets
import app.viewfinder.ptp.ptpip.PtpIpType
import app.viewfinder.ptp.vendor.nikon.NikonOp
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * A pretend Nikon that speaks PTP/IP on a local port, written from the CIPA DC-005 packet layout.
 * Tests use it to exercise the real transport end to end; `runFakeCamera` serves it to the emulator.
 */
class FakePtpIpCamera(
    objects: List<FakeObject>,
    private val model: String = "D5600",
    private val vendorExtensionId: Long = 0x0AL,
    private val manufacturer: String = "Nikon Corporation",
    /** Answer InitCommandRequest with InitFail(rejected) instead of an ack. */
    private val rejectConnections: Boolean = false,
    /** Largest Data packet the fake sends, to force the client to reassemble. */
    private val dataPacketSize: Int = 7_000,
    /** Caps how fast file data goes out (bytes per second), like a real camera's Wi-Fi; null is as fast as possible. */
    private val bytesPerSecond: Long? = null,
    /** What InitiateCapture "shoots", given the new handle. */
    private val makeShot: (Long) -> FakeObject = { FakeObject(it, "DSC_%04d.JPG".format(it), ByteArray(50_000) { i -> (i % 251).toByte() }) },
    /** Answer Nikon.InitiateCaptureRecInMedia with InvalidStatus, as the D5600 did in testing. */
    private val refuseCaptureInMedia: Boolean = false,
    /** One live view JPEG; the fake prefixes Nikon's header. */
    private val liveFrame: () -> ByteArray = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3) },
) : AutoCloseable {

    /** A device property: its PTP data type, whether it can be set, and its value/choices. */
    class FakeProp(val type: Int, val writable: Boolean, var current: PropValue, val choices: List<PropValue> = emptyList())

    /** Standard properties are listed in DeviceInfo; codes ≥ 0xD000 only via Nikon.GetVendorPropCodes. */
    val props: MutableMap<Int, FakeProp> = Collections.synchronizedMap(defaultProps())

    /** The parameters of the last call of each operation. */
    val lastParams: MutableMap<Int, List<Long>> = Collections.synchronizedMap(mutableMapOf())

    @Volatile var liveViewOn = false
        private set

    @Volatile var movieOn = false
        private set

    /** The next DeviceReady answers this code once, e.g. 0xA00E when the lens hit the end of its travel. */
    @Volatile var nextDeviceReady: Int = Rc.OK

    data class FakeObject(
        val handle: Long,
        val filename: String,
        val content: ByteArray,
        val thumb: ByteArray = byteArrayOf(1, 2, 3),
        val preview: ByteArray = byteArrayOf(4, 5, 6, 7),
        val captureDate: String = "20261121T143005",
        val format: Int = Format.ExifJpeg,
        val width: Long = 6000,
        val height: Long = 4000,
    )

    private val objects = Collections.synchronizedList(objects.toMutableList())
    private val pendingNikonEvents = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())

    /** Every operation code the fake has been asked to run, in order. */
    val operations: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    private lateinit var server: ServerSocket
    val port: Int get() = server.localPort

    fun start(port: Int = 0, bindAddress: InetAddress = InetAddress.getLoopbackAddress()): FakePtpIpCamera {
        server = ServerSocket(port, 4, bindAddress)
        Thread({ acceptLoop() }, "fake-camera").apply { isDaemon = true }.start()
        return this
    }

    /** Simulates a shot taken on the camera: a new object plus a queued ObjectAdded event. */
    fun addObject(obj: FakeObject) {
        objects += obj
        pendingNikonEvents += Ev.ObjectAdded to obj.handle
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val cmd = runCatching { server.accept() }.getOrNull() ?: return
            runCatching { session(cmd) }
            runCatching { cmd.close() }
        }
    }

    private fun session(cmd: Socket) {
        val cmdIn = DataInputStream(cmd.getInputStream())
        val cmdOut = cmd.getOutputStream()
        val (type, _) = readPacket(cmdIn)
        check(type == PtpIpType.InitCommandRequest) { "expected InitCommandRequest, got $type" }
        if (rejectConnections) {
            send(cmdOut, PtpIpType.InitFail, PtpWriter().u32(1).toByteArray())
            return
        }
        send(
            cmdOut,
            PtpIpType.InitCommandAck,
            PtpWriter().u32(1).bytes(ByteArray(16) { 0x11 }).utf16z(model).u32(PtpIpPackets.PROTOCOL_VERSION).toByteArray(),
        )
        val evt = server.accept()
        val evtIn = DataInputStream(evt.getInputStream())
        val (evtType, _) = readPacket(evtIn)
        check(evtType == PtpIpType.InitEventRequest) { "expected InitEventRequest, got $evtType" }
        send(evt.getOutputStream(), PtpIpType.InitEventAck, ByteArray(0))

        try {
            while (true) {
                val (opType, payload) = readPacket(cmdIn)
                check(opType == PtpIpType.OperationRequest) { "expected OperationRequest, got $opType" }
                val r = PtpReader(payload)
                val phase = r.u32().toInt()
                val code = r.u16()
                val tid = r.u32()
                val params = buildList { while (r.remaining >= 4) add(r.u32()) }
                val dataOut = if (phase == 2) readDataOut(cmdIn) else null
                operations += code
                lastParams[code] = params
                handle(code, params, tid, cmdOut, dataOut)
                if (code == Op.CloseSession) return
            }
        } finally {
            runCatching { evt.close() }
        }
    }

    private fun readDataOut(input: DataInputStream): ByteArray {
        val (start, _) = readPacket(input)
        check(start == PtpIpType.StartData) { "expected StartData, got $start" }
        val collected = java.io.ByteArrayOutputStream()
        while (true) {
            val (type, payload) = readPacket(input)
            collected.write(payload, 4, payload.size - 4)
            if (type == PtpIpType.EndData) return collected.toByteArray()
        }
    }

    private fun handle(code: Int, p: List<Long>, tid: Long, out: OutputStream, dataOut: ByteArray?) {
        fun find(handle: Long) = synchronized(objects) { objects.firstOrNull { it.handle == handle } }
        when (code) {
            Op.OpenSession, Op.CloseSession -> respond(out, tid, Rc.OK)
            NikonOp.DeviceReady -> respond(out, tid, nextDeviceReady).also { nextDeviceReady = Rc.OK }
            Op.GetDeviceInfo -> data(out, tid, deviceInfo())
            Op.GetStorageIDs -> data(out, tid, PtpWriter().u32Array(listOf(STORAGE)).toByteArray())
            Op.GetStorageInfo -> data(
                out, tid,
                PtpWriter().u16(4).u16(2).u16(0).u64(64L shl 30).u64(40L shl 30).u32(1234)
                    .ptpString("SD").ptpString("NIKON D5600").toByteArray(),
            )
            Op.GetObjectHandles -> data(out, tid, PtpWriter().u32Array(synchronized(objects) { objects.map { it.handle } }).toByteArray())
            Op.GetObjectInfo -> find(p[0])?.let { data(out, tid, objectInfo(it)) } ?: respond(out, tid, Rc.InvalidObjectHandle)
            Op.GetObject -> find(p[0])?.let { data(out, tid, it.content) } ?: respond(out, tid, Rc.InvalidObjectHandle)
            Op.GetPartialObject -> find(p[0])?.let {
                val from = p[1].toInt().coerceAtMost(it.content.size)
                val to = (from + p[2].toInt()).coerceAtMost(it.content.size)
                data(out, tid, it.content.copyOfRange(from, to))
            } ?: respond(out, tid, Rc.InvalidObjectHandle)
            Op.GetThumb -> find(p[0])?.let { data(out, tid, it.thumb) } ?: respond(out, tid, Rc.InvalidObjectHandle)
            NikonOp.GetLargeThumb -> find(p[0])?.let { data(out, tid, it.preview) } ?: respond(out, tid, Rc.InvalidObjectHandle)
            Op.GetDevicePropValue -> props[p[0].toInt()]?.let { data(out, tid, encodeValue(it.type, it.current)) } ?: respond(out, tid, Rc.DevicePropNotSupported)
            Op.GetDevicePropDesc -> props[p[0].toInt()]?.let { data(out, tid, propDesc(p[0].toInt(), it)) } ?: respond(out, tid, Rc.DevicePropNotSupported)
            Op.SetDevicePropValue -> {
                val prop = props[p[0].toInt()]
                when {
                    prop == null -> respond(out, tid, Rc.DevicePropNotSupported)
                    !prop.writable -> respond(out, tid, 0x200F) // AccessDenied
                    else -> {
                        val value = PtpReader(dataOut ?: ByteArray(0)).value(prop.type)
                        if (prop.choices.isNotEmpty() && value !in prop.choices) {
                            respond(out, tid, Rc.InvalidParameter)
                        } else {
                            prop.current = value
                            pendingNikonEvents += Ev.DevicePropChanged to p[0]
                            respond(out, tid, Rc.OK)
                        }
                    }
                }
            }
            NikonOp.GetVendorPropCodes -> data(out, tid, PtpWriter().u16Array(props.keys.filter { it >= 0xD000 }.sorted()).toByteArray())
            Op.DeleteObject -> if (objects.removeIf { it.handle == p[0] }) {
                pendingNikonEvents += Ev.ObjectRemoved to p[0]
                respond(out, tid, Rc.OK)
            } else {
                respond(out, tid, Rc.InvalidObjectHandle)
            }
            NikonOp.StartLiveView -> {
                liveViewOn = true
                respond(out, tid, Rc.OK)
            }
            NikonOp.EndLiveView -> {
                liveViewOn = false
                respond(out, tid, Rc.OK)
            }
            NikonOp.GetLiveViewImg -> if (!liveViewOn) {
                respond(out, tid, 0xA00B) // Nikon.NotLiveView
            } else {
                // A 384-byte header whose bytes 4..7 hold the focus area size (big-endian), then the JPEG.
                val header = ByteArray(384).also {
                    it[4] = (LV_AREA_W shr 8).toByte(); it[5] = LV_AREA_W.toByte()
                    it[6] = (LV_AREA_H shr 8).toByte(); it[7] = LV_AREA_H.toByte()
                }
                data(out, tid, header + liveFrame())
            }
            NikonOp.AfDrive, NikonOp.ChangeAfArea, NikonOp.AfDriveCancel, NikonOp.MfDrive -> respond(out, tid, Rc.OK)
            NikonOp.StartMovieRecInCard -> { movieOn = true; respond(out, tid, Rc.OK) }
            NikonOp.EndMovieRec -> { movieOn = false; respond(out, tid, Rc.OK) }
            NikonOp.TerminateCapture -> {
                val next = synchronized(objects) { (objects.maxOfOrNull { it.handle } ?: 0) + 1 }
                addObject(makeShot(next))
                respond(out, tid, Rc.OK)
            }
            NikonOp.InitiateCaptureRecInMedia -> if (refuseCaptureInMedia) respond(out, tid, 0xA004) else {
                val next = synchronized(objects) { (objects.maxOfOrNull { it.handle } ?: 0) + 1 }
                addObject(makeShot(next))
                respond(out, tid, Rc.OK)
            }
            NikonOp.GetEvent -> {
                val events = synchronized(pendingNikonEvents) { pendingNikonEvents.toList().also { pendingNikonEvents.clear() } }
                val w = PtpWriter().u16(events.size)
                events.forEach { (c, param) -> w.u16(c).u32(param) }
                data(out, tid, w.toByteArray())
            }
            Op.InitiateCapture -> {
                val next = synchronized(objects) { (objects.maxOfOrNull { it.handle } ?: 0) + 1 }
                addObject(makeShot(next))
                respond(out, tid, Rc.OK)
            }
            else -> respond(out, tid, Rc.OperationNotSupported)
        }
    }

    private fun deviceInfo(): ByteArray = PtpWriter()
        .u16(100).u32(vendorExtensionId).u16(100).ptpString("microsoft.com: 1.0").u16(0)
        .u16Array(
            listOf(
                Op.GetDeviceInfo, Op.OpenSession, Op.CloseSession, Op.GetStorageIDs, Op.GetStorageInfo,
                Op.GetObjectHandles, Op.GetObjectInfo, Op.GetObject, Op.GetThumb, Op.GetPartialObject,
                Op.InitiateCapture, Op.GetDevicePropValue, Op.GetDevicePropDesc, Op.SetDevicePropValue, Op.DeleteObject,
                NikonOp.GetEvent, NikonOp.GetLargeThumb, NikonOp.DeviceReady, NikonOp.GetVendorPropCodes,
                NikonOp.StartLiveView, NikonOp.EndLiveView, NikonOp.GetLiveViewImg, NikonOp.AfDrive,
                NikonOp.ChangeAfArea, NikonOp.InitiateCaptureRecInMedia, NikonOp.MfDrive, NikonOp.AfDriveCancel,
                NikonOp.StartMovieRecInCard, NikonOp.EndMovieRec, NikonOp.TerminateCapture,
            ),
        )
        .u16Array(listOf(Ev.ObjectAdded))
        .u16Array(props.keys.filter { it < 0xD000 }.sorted())
        .u16Array(listOf(Format.ExifJpeg))
        .u16Array(listOf(Format.ExifJpeg, Format.Undefined))
        .ptpString(manufacturer).ptpString(model).ptpString("V1.20").ptpString("000000123456")
        .toByteArray()

    private fun objectInfo(o: FakeObject): ByteArray = PtpWriter()
        .u32(STORAGE).u16(o.format).u16(0).u32(o.content.size.toLong())
        .u16(0x3808).u32(o.thumb.size.toLong()).u32(160).u32(120)
        .u32(o.width).u32(o.height).u32(24)
        .u32(0).u16(0).u32(0).u32(0)
        .ptpString(o.filename).ptpString(o.captureDate).ptpString("").ptpString("")
        .toByteArray()

    private fun data(out: OutputStream, tid: Long, payload: ByteArray) {
        out.write(PtpIpPackets.startData(tid, payload.size.toLong()))
        var offset = 0
        while (payload.size - offset > dataPacketSize) {
            val piece = payload.copyOfRange(offset, offset + dataPacketSize)
            send(out, PtpIpType.Data, PtpWriter().u32(tid).bytes(piece).toByteArray(), flush = bytesPerSecond != null)
            offset += dataPacketSize
            bytesPerSecond?.let { Thread.sleep(piece.size * 1000L / it) }
        }
        out.write(PtpIpPackets.endData(tid, payload.copyOfRange(offset, payload.size)))
        respond(out, tid, Rc.OK)
    }

    private fun respond(out: OutputStream, tid: Long, rc: Int) {
        send(out, PtpIpType.OperationResponse, PtpWriter().u16(rc).u32(tid).toByteArray())
    }

    private fun send(out: OutputStream, type: Int, payload: ByteArray, flush: Boolean = true) {
        out.write(PtpIpPackets.packet(type, payload))
        if (flush) out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(8).also { input.readFully(it) }
        val r = PtpReader(header)
        val length = r.u32().toInt()
        val type = r.u32().toInt()
        return type to ByteArray(length - 8).also { input.readFully(it) }
    }

    private fun propDesc(code: Int, prop: FakeProp): ByteArray {
        val w = PtpWriter().u16(code).u16(prop.type).u8(if (prop.writable) 1 else 0)
            .bytes(encodeValue(prop.type, prop.current)).bytes(encodeValue(prop.type, prop.current))
        if (prop.choices.isEmpty()) {
            w.u8(0)
        } else {
            w.u8(2).u16(prop.choices.size)
            prop.choices.forEach { w.bytes(encodeValue(prop.type, it)) }
        }
        return w.toByteArray()
    }

    companion object {
        const val STORAGE = 0x00010001L
        const val LV_AREA_W = 6000
        const val LV_AREA_H = 4000

        private fun nums(vararg v: Long) = v.map { PropValue.Num(it) }

        /** Roughly what a D5600 in aperture priority reports. */
        fun defaultProps(): MutableMap<Int, FakeProp> = mutableMapOf(
            Prop.BatteryLevel to FakeProp(DataType.UINT8, false, PropValue.Num(87)),
            0x5004 to FakeProp(DataType.UINT8, true, PropValue.Num(2), nums(0, 1, 2, 4, 5, 6, 7)),
            0x5005 to FakeProp(DataType.UINT16, true, PropValue.Num(2), nums(2, 4, 6, 5, 7, 0x8010, 0x8011, 0x8013)),
            0x5007 to FakeProp(DataType.UINT16, true, PropValue.Num(560), nums(350, 400, 450, 500, 560, 630, 710, 800, 900, 1000, 1100, 1300, 1400, 1600, 1800, 2000, 2200)),
            0x5008 to FakeProp(DataType.UINT32, false, PropValue.Num(3500)),
            0x500A to FakeProp(DataType.UINT16, true, PropValue.Num(0x8010), nums(0x8010, 0x8011, 0x8012, 1)),
            0x500B to FakeProp(DataType.UINT16, true, PropValue.Num(3), nums(2, 3, 4, 0x8010)),
            0x500E to FakeProp(DataType.UINT16, false, PropValue.Num(3)),
            0x500F to FakeProp(DataType.UINT16, true, PropValue.Num(200), nums(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800, 25600)),
            0x5010 to FakeProp(DataType.INT16, true, PropValue.Num(0), (-15..15).map { PropValue.Num((it * 1000 / 3).toLong()) }),
            Prop.DateTime to FakeProp(DataType.STR, true, PropValue.Str("20250101T000000")),
            0x5013 to FakeProp(DataType.UINT16, true, PropValue.Num(1), nums(1, 2, 0x8010, 0x8011, 0x8016)),
            0xD100 to FakeProp(DataType.UINT32, false, PropValue.Num(0x000100FAL)),
            0xD10B to FakeProp(DataType.UINT8, true, PropValue.Num(0), nums(0, 1)),
            0xD161 to FakeProp(DataType.UINT8, true, PropValue.Num(0), nums(0, 1, 2, 3, 4)),
            0xD407 to FakeProp(DataType.UINT8, false, PropValue.Num(1)),
            0xD1B5 to FakeProp(DataType.UINT8, false, PropValue.Num(0)),
            0xD0E3 to FakeProp(DataType.UINT32, false, PropValue.Num(1800)),
            0xD0E5 to FakeProp(DataType.UINT16, false, PropValue.Num(350)),
            0xD10E to FakeProp(DataType.UINT8, false, PropValue.Num(2)),
        )
    }
}
