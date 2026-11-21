package app.viewfinder.ptp

import app.viewfinder.ptp.FakePtpIpCamera.FakeObject
import app.viewfinder.ptp.camera.CameraDrivers
import app.viewfinder.ptp.camera.CameraEvent
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.camera.PtpCamera
import app.viewfinder.ptp.ptpip.PtpIpTransport
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEndToEndTest {

    private val jpeg = Random(42).nextBytes(2_500_000)
    private val raw = Random(7).nextBytes(300_001)
    private var fake: FakePtpIpCamera? = null

    @After
    fun tearDown() {
        fake?.close()
    }

    private fun startFake(vararg objects: FakeObject, reject: Boolean = false, vendor: Long = 0x0AL, maker: String = "Nikon Corporation") =
        FakePtpIpCamera(objects.toList(), rejectConnections = reject, vendorExtensionId = vendor, manufacturer = maker)
            .start().also { fake = it }

    private suspend fun connect(cam: FakePtpIpCamera) =
        CameraDrivers.open(PtpIpTransport.connect("127.0.0.1", ByteArray(16), "test", port = cam.port))

    @Test
    fun `lists, describes and downloads photos from a Nikon`() = runBlocking {
        val cam = startFake(
            FakeObject(1, "DSC_0001.JPG", jpeg),
            FakeObject(2, "DSC_0001.NEF", raw, format = Format.Undefined),
        )
        val camera = connect(cam)

        assertEquals("D5600", camera.info.model)
        assertEquals("Nikon", camera.info.driver)
        assertTrue(Capability.LargePreview in camera.capabilities)
        assertEquals(listOf(1L, 2L), camera.objectHandles())
        assertEquals("DSC_0001.NEF", camera.objectInfo(2).filename)
        assertEquals(1234L, camera.storages().single().freeImages)
        assertEquals(87, camera.batteryLevel())
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), camera.preview(1))

        val out = ByteArrayOutputStream()
        var lastProgress = 0L
        camera.download(1, jpeg.size.toLong(), out) { done, _ -> lastProgress = done }
        assertArrayEquals(jpeg, out.toByteArray())
        assertEquals(jpeg.size.toLong(), lastProgress)
        // 2.5 MB in 1 MiB pieces: three GetPartialObject calls, never one big GetObject.
        assertEquals(3, cam.operations.count { it == Op.GetPartialObject })
        assertEquals(0, cam.operations.count { it == Op.GetObject })

        camera.close()
    }

    @Test
    fun `whole-object download reassembles many Data packets`() = runBlocking {
        val cam = startFake(FakeObject(9, "DSC_0009.NEF", raw))
        val camera = connect(cam)

        // Size unknown (0) forces the single GetObject path.
        val out = ByteArrayOutputStream()
        camera.download(9, 0, out)
        assertArrayEquals(raw, out.toByteArray())
        assertEquals(1, cam.operations.count { it == Op.GetObject })
        camera.close()
    }

    @Test
    fun `a shot taken on the camera arrives as ObjectAdded through Nikon event polling`() = runBlocking {
        val cam = startFake(FakeObject(1, "DSC_0001.JPG", jpeg))
        val camera = connect(cam)

        val firstAdded = async(start = CoroutineStart.UNDISPATCHED) {
            camera.events.filterIsInstance<CameraEvent.ObjectAdded>().first()
        }
        cam.addObject(FakeObject(5, "DSC_0005.JPG", raw))
        val added = withTimeout(5_000) { firstAdded.await() }

        assertEquals(5L, added.handle)
        assertEquals(listOf(1L, 5L), camera.objectHandles())
        camera.close()
    }

    @Test
    fun `a camera from another brand falls back to the generic driver`() = runBlocking {
        val cam = startFake(FakeObject(1, "IMG_0001.JPG", jpeg), vendor = 0x0BL, maker = "Acme")
        val camera = connect(cam)

        assertEquals("Generic PTP", camera.info.driver)
        assertTrue(camera is PtpCamera && camera::class == PtpCamera::class)
        // Same data, standard operations only: the preview falls back to the small thumbnail.
        assertArrayEquals(byteArrayOf(1, 2, 3), camera.preview(1))
        camera.close()
    }

    @Test
    fun `a refused connection says why`() {
        val cam = startFake(reject = true)
        val e = assertThrows(PtpException::class.java) { runBlocking { connect(cam) } }
        assertTrue(e.message!!, e.message!!.contains("rejected this phone"))
    }
}
