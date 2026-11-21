package app.viewfinder.ptp.camera

import app.viewfinder.ptp.ByteArraySink
import app.viewfinder.ptp.DeviceInfo
import app.viewfinder.ptp.Op
import app.viewfinder.ptp.PtpLog
import app.viewfinder.ptp.PtpResponseException
import app.viewfinder.ptp.PtpTransport
import app.viewfinder.ptp.Rc
import app.viewfinder.ptp.vendor.nikon.NikonDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A brand driver: recognizes its cameras from their DeviceInfo and builds the matching [PtpCamera]. */
interface CameraDriver {
    val name: String
    fun matches(info: DeviceInfo): Boolean
    fun create(transport: PtpTransport, info: DeviceInfo, scope: CoroutineScope, log: PtpLog): PtpCamera
}

object CameraDrivers {
    /** Add new brands here. The first driver that matches wins; anything else gets plain PTP. */
    val all: List<CameraDriver> = listOf(NikonDriver)

    /** Opens a PTP session on [transport], identifies the camera and hands it to the right driver. */
    suspend fun open(transport: PtpTransport, log: PtpLog = PtpLog.None): Camera {
        val session = transport.transaction(Op.OpenSession, listOf(SESSION_ID))
        if (!session.isOk && session.code != Rc.SessionAlreadyOpen) {
            transport.close()
            throw PtpResponseException(Op.OpenSession, session.code)
        }
        val sink = ByteArraySink()
        val response = transport.transaction(Op.GetDeviceInfo, dataIn = sink)
        if (!response.isOk) {
            transport.close()
            throw PtpResponseException(Op.GetDeviceInfo, response.code)
        }
        val info = DeviceInfo.parse(sink.toByteArray())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val driver = all.firstOrNull { it.matches(info) }
        val camera = driver?.create(transport, info, scope, log) ?: PtpCamera(transport, info, scope, log)
        log.log("${info.manufacturer} ${info.model} (firmware ${info.deviceVersion}) → ${camera.driverName} driver")
        camera.onSessionOpened()
        return camera
    }

    private const val SESSION_ID = 1L
}
