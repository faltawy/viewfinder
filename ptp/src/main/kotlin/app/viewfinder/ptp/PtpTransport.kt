package app.viewfinder.ptp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

open class PtpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The camera answered an operation with something other than OK. */
class PtpResponseException(val operation: Int, val responseCode: Int) :
    PtpException("${Op.name(operation)} failed: ${Rc.name(responseCode)}")

data class PtpResponse(val code: Int, val params: List<Long>) {
    val isOk: Boolean get() = code == Rc.OK
}

data class PtpEvent(val code: Int, val params: List<Long>)

/** Receives the data phase of an operation, possibly in many pieces. */
interface DataSink {
    /** Called once with the total size announced by the camera, when it announces one. */
    fun begin(totalLength: Long) {}
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

class ByteArraySink : DataSink {
    private val out = ByteArrayOutputStream()
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
    fun toByteArray(): ByteArray = out.toByteArray()
}

fun interface PtpLog {
    fun log(line: String)

    companion object {
        val None = PtpLog { }
    }
}

/**
 * One connection to a camera that carries PTP transactions: Wi-Fi (PTP/IP) today, USB or others later.
 * Transactions are serialized; a transport is unusable once [connected] turns false.
 */
interface PtpTransport : Closeable {
    val connected: StateFlow<Boolean>

    /** Events the camera pushes on its own (PTP/IP event channel, USB interrupt endpoint). */
    val events: SharedFlow<PtpEvent>

    /**
     * Runs one operation. [dataOut] sends a data phase to the camera; [dataIn] receives one from it.
     * Returns the camera's response, OK or not; it throws only on transport failure.
     */
    suspend fun transaction(
        code: Int,
        params: List<Long> = emptyList(),
        dataOut: ByteArray? = null,
        dataIn: DataSink? = null,
    ): PtpResponse
}
