package app.viewfinder.ptp.ptpip

import app.viewfinder.ptp.DataSink
import app.viewfinder.ptp.Op
import app.viewfinder.ptp.PtpEvent
import app.viewfinder.ptp.PtpException
import app.viewfinder.ptp.PtpLog
import app.viewfinder.ptp.PtpReader
import app.viewfinder.ptp.PtpResponse
import app.viewfinder.ptp.PtpTransport
import app.viewfinder.ptp.Rc
import app.viewfinder.ptp.hex32
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PTP over TCP: a command/data socket and an event socket, both to port 15740.
 * Create one with [connect].
 */
class PtpIpTransport private constructor(
    private val commandSocket: Socket,
    private val eventSocket: Socket,
    /** The name the camera reported in its InitCommandAck. */
    val cameraName: String,
    private val log: PtpLog,
) : PtpTransport {

    private val cmdIn = PacketInput(commandSocket.getInputStream())
    private val cmdOut = BufferedOutputStream(commandSocket.getOutputStream(), 64 * 1024)
    private val evtOut = eventSocket.getOutputStream()

    private val mutex = Mutex()
    private var nextTransactionId = 1L

    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PtpEvent> = _events.asSharedFlow()

    private val eventThread = Thread(::readEvents, "ptpip-events").apply { isDaemon = true }

    override suspend fun transaction(
        code: Int,
        params: List<Long>,
        dataOut: ByteArray?,
        dataIn: DataSink?,
    ): PtpResponse = mutex.withLock {
        // A half-finished transaction leaves the stream unusable, so once started it runs to the end.
        withContext(Dispatchers.IO + NonCancellable) {
            if (!_connected.value) throw PtpException("Not connected to the camera")
            val tid = if (code == Op.OpenSession) 0L.also { nextTransactionId = 1 } else nextTransactionId++
            try {
                log.log("→ ${Op.name(code)} tid=$tid ${params.joinToString(prefix = "[", postfix = "]") { hex32(it) }}")
                val phase = if (dataOut != null) DataPhase.Out else DataPhase.NoneOrIn
                cmdOut.write(PtpIpPackets.operationRequest(phase, code, tid, params))
                if (dataOut != null) {
                    cmdOut.write(PtpIpPackets.startData(tid, dataOut.size.toLong()))
                    cmdOut.write(PtpIpPackets.endData(tid, dataOut))
                }
                cmdOut.flush()
                readResponse(code, dataIn).also { log.log("← ${Rc.name(it.code)} ${Op.name(code)}") }
            } catch (e: IOException) {
                log.log("✕ ${Op.name(code)}: ${e.message}")
                fail()
                throw if (e is PtpException) e else PtpException("Lost the camera connection: ${e.message}", e)
            }
        }
    }

    private fun readResponse(code: Int, dataIn: DataSink?): PtpResponse {
        var received = 0L
        while (true) {
            val header = cmdIn.header()
            when (header.type) {
                PtpIpType.StartData -> {
                    val r = PtpReader(cmdIn.payload(header.payloadLength))
                    r.u32()
                    val total = r.u64()
                    log.log("  data: $total bytes")
                    dataIn?.begin(total)
                }
                PtpIpType.Data, PtpIpType.EndData -> {
                    cmdIn.skip(4) // transaction id
                    val n = header.payloadLength - 4
                    cmdIn.stream(n, dataIn)
                    received += n
                }
                PtpIpType.OperationResponse -> {
                    val r = PtpReader(cmdIn.payload(header.payloadLength))
                    val rc = r.u16()
                    r.u32()
                    val params = buildList { while (r.remaining >= 4) add(r.u32()) }
                    return PtpResponse(rc, params)
                }
                PtpIpType.Event -> {
                    // Not expected here, but some cameras do it; deliver it like any other event.
                    parseEvent(cmdIn.payload(header.payloadLength))?.let { _events.tryEmit(it) }
                }
                else -> {
                    log.log("  skipping unexpected packet type ${header.type} during ${Op.name(code)}")
                    cmdIn.skip(header.payloadLength)
                }
            }
        }
    }

    private fun readEvents() {
        val input = PacketInput(eventSocket.getInputStream())
        try {
            while (_connected.value) {
                val header = try {
                    input.header()
                } catch (_: SocketTimeoutException) {
                    continue // the event channel is quiet most of the time
                }
                when (header.type) {
                    PtpIpType.Event -> parseEvent(input.payload(header.payloadLength))?.let {
                        log.log("⚡ event ${"0x%04X".format(it.code)} ${it.params.map(::hex32)}")
                        _events.tryEmit(it)
                    }
                    PtpIpType.ProbeRequest -> {
                        input.skip(header.payloadLength)
                        synchronized(evtOut) {
                            evtOut.write(PtpIpPackets.probeResponse())
                            evtOut.flush()
                        }
                    }
                    else -> input.skip(header.payloadLength)
                }
            }
        } catch (e: IOException) {
            if (_connected.value) log.log("✕ event channel closed: ${e.message}")
            fail()
        }
    }

    private fun parseEvent(payload: ByteArray): PtpEvent? = runCatching {
        val r = PtpReader(payload)
        val code = r.u16()
        r.u32()
        PtpEvent(code, buildList { while (r.remaining >= 4) add(r.u32()) })
    }.getOrNull()

    private fun fail() {
        if (_connected.compareAndSet(true, false)) {
            runCatching { commandSocket.close() }
            runCatching { eventSocket.close() }
        }
    }

    override fun close() {
        log.log("closing connection")
        fail()
    }

    companion object {
        const val DEFAULT_PORT = 15740
        private const val CONNECT_TIMEOUT_MS = 8_000
        /** Long, because some cameras wait for the user to confirm a new phone on their screen. */
        private const val INIT_TIMEOUT_MS = 90_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Opens both PTP/IP channels. [socketFactory] lets Android route the sockets over the camera's
         * Wi-Fi even when that network has no internet. [guid] must stay the same across launches:
         * cameras remember the phones they have accepted by it.
         */
        suspend fun connect(
            host: String,
            guid: ByteArray,
            clientName: String,
            port: Int = DEFAULT_PORT,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
            log: PtpLog = PtpLog.None,
        ): PtpIpTransport = withContext(Dispatchers.IO) {
            log.log("connecting to $host:$port")
            val cmd = open(socketFactory, host, port)
            try {
                cmd.soTimeout = INIT_TIMEOUT_MS
                cmd.getOutputStream().apply { write(PtpIpPackets.initCommandRequest(guid, clientName)); flush() }
                log.log("→ InitCommandRequest as \"$clientName\" (confirm on the camera if it asks)")
                val input = PacketInput(cmd.getInputStream())
                val header = input.header()
                val payload = input.payload(header.payloadLength)
                val (connectionNumber, cameraName) = when (header.type) {
                    PtpIpType.InitCommandAck -> PtpReader(payload).run { u32() to also { bytes(16) }.utf16z() }
                    PtpIpType.InitFail -> {
                        val reason = runCatching { PtpReader(payload).u32() }.getOrDefault(3L)
                        throw PtpException("Camera refused the connection: ${initFailReason(reason)}")
                    }
                    else -> throw PtpException("Unexpected reply to InitCommandRequest (type ${header.type})")
                }
                log.log("← InitCommandAck from \"$cameraName\", connection $connectionNumber")
                cmd.soTimeout = READ_TIMEOUT_MS

                val evt = open(socketFactory, host, port)
                try {
                    evt.soTimeout = READ_TIMEOUT_MS
                    evt.getOutputStream().apply { write(PtpIpPackets.initEventRequest(connectionNumber)); flush() }
                    val evtInput = PacketInput(evt.getInputStream())
                    val ack = evtInput.header()
                    evtInput.skip(ack.payloadLength)
                    if (ack.type != PtpIpType.InitEventAck) {
                        throw PtpException("Camera refused the event channel (type ${ack.type})")
                    }
                    log.log("← InitEventAck, connected")
                    PtpIpTransport(cmd, evt, cameraName, log).also { it.eventThread.start() }
                } catch (e: Throwable) {
                    runCatching { evt.close() }
                    throw e
                }
            } catch (e: Throwable) {
                runCatching { cmd.close() }
                throw if (e is IOException && e !is PtpException) PtpException("Couldn't reach the camera at $host: ${e.message}", e) else e
            }
        }

        private fun open(factory: SocketFactory, host: String, port: Int): Socket =
            factory.createSocket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
    }
}

private class PacketHeader(val length: Long, val type: Int) {
    val payloadLength: Int get() = (length - PtpIpPackets.HEADER_SIZE).toInt()
}

/**
 * Reads PTP/IP packets from a socket. Unbuffered on purpose: the init handshake hands the stream
 * on to the transport, so no bytes may be read ahead of the current packet.
 */
private class PacketInput(stream: java.io.InputStream) {
    private val input = DataInputStream(stream)
    private val headerBuf = ByteArray(PtpIpPackets.HEADER_SIZE)
    private val chunk = ByteArray(64 * 1024)

    fun header(): PacketHeader {
        input.readFully(headerBuf)
        val r = PtpReader(headerBuf)
        val length = r.u32()
        val type = r.u32().toInt()
        if (length < PtpIpPackets.HEADER_SIZE || length > MAX_PACKET) {
            throw PtpException("Corrupt PTP/IP packet (length $length, type $type)")
        }
        return PacketHeader(length, type)
    }

    fun payload(n: Int): ByteArray = ByteArray(n).also { input.readFully(it) }

    fun skip(n: Int) = stream(n, null)

    /** Copies [n] bytes to [sink] in chunks, so large objects never sit in memory whole. */
    fun stream(n: Int, sink: DataSink?) {
        var left = n
        while (left > 0) {
            val step = minOf(left, chunk.size)
            input.readFully(chunk, 0, step)
            sink?.write(chunk, 0, step)
            left -= step
        }
    }

    companion object {
        private const val MAX_PACKET = Int.MAX_VALUE.toLong()
    }
}
