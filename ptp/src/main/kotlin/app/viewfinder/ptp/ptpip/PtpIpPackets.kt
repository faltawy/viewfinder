package app.viewfinder.ptp.ptpip

import app.viewfinder.ptp.PtpWriter

/** PTP/IP packet types (CIPA DC-005). Every packet is `u32 length (incl. header) | u32 type | payload`. */
object PtpIpType {
    const val InitCommandRequest = 1
    const val InitCommandAck = 2
    const val InitEventRequest = 3
    const val InitEventAck = 4
    const val InitFail = 5
    const val OperationRequest = 6
    const val OperationResponse = 7
    const val Event = 8
    const val StartData = 9
    const val Data = 10
    const val Cancel = 11
    const val EndData = 12
    const val ProbeRequest = 13
    const val ProbeResponse = 14
}

/** Data phase field of an OperationRequest. */
object DataPhase {
    const val NoneOrIn = 1
    const val Out = 2
}

object PtpIpPackets {
    const val HEADER_SIZE = 8
    const val PROTOCOL_VERSION = 0x00010000L

    fun packet(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        PtpWriter(HEADER_SIZE + payload.size)
            .u32((HEADER_SIZE + payload.size).toLong())
            .u32(type.toLong())
            .bytes(payload)
            .toByteArray()

    fun initCommandRequest(guid: ByteArray, name: String): ByteArray {
        require(guid.size == 16) { "PTP/IP GUIDs are 16 bytes" }
        return packet(
            PtpIpType.InitCommandRequest,
            PtpWriter().bytes(guid).utf16z(name).u32(PROTOCOL_VERSION).toByteArray(),
        )
    }

    fun initEventRequest(connectionNumber: Long): ByteArray =
        packet(PtpIpType.InitEventRequest, PtpWriter().u32(connectionNumber).toByteArray())

    fun operationRequest(dataPhase: Int, code: Int, transactionId: Long, params: List<Long>): ByteArray {
        require(params.size <= 5) { "PTP operations take at most 5 parameters" }
        val w = PtpWriter().u32(dataPhase.toLong()).u16(code).u32(transactionId)
        params.forEach { w.u32(it) }
        return packet(PtpIpType.OperationRequest, w.toByteArray())
    }

    fun startData(transactionId: Long, totalLength: Long): ByteArray =
        packet(PtpIpType.StartData, PtpWriter().u32(transactionId).u64(totalLength).toByteArray())

    fun endData(transactionId: Long, data: ByteArray): ByteArray =
        packet(PtpIpType.EndData, PtpWriter(4 + data.size).u32(transactionId).bytes(data).toByteArray())

    fun probeResponse(): ByteArray = packet(PtpIpType.ProbeResponse)
}

/** Why a camera refused an InitCommandRequest, per CIPA DC-005. */
fun initFailReason(code: Long): String = when (code) {
    1L -> "the camera rejected this phone (pair it on the camera first)"
    2L -> "the camera is busy with another connection"
    3L -> "unspecified failure"
    else -> "reason 0x%08X".format(code)
}
