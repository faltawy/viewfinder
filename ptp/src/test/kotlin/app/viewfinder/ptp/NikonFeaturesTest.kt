package app.viewfinder.ptp

import app.viewfinder.ptp.FakePtpIpCamera.FakeObject
import app.viewfinder.ptp.camera.Camera
import app.viewfinder.ptp.camera.CameraDrivers
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.ptpip.PtpIpTransport
import app.viewfinder.ptp.vendor.nikon.NikonOp
import app.viewfinder.ptp.vendor.nikon.NikonProps
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NikonFeaturesTest {

    private val fake = FakePtpIpCamera(
        listOf(FakeObject(1, "DSC_0001.JPG", ByteArray(10) { 1 }), FakeObject(2, "DSC_0002.JPG", ByteArray(10) { 2 })),
    ).start()

    @After
    fun tearDown() = fake.close()

    private fun withCamera(block: suspend (Camera) -> Unit) = runBlocking {
        val camera = CameraDrivers.open(PtpIpTransport.connect("127.0.0.1", ByteArray(16), "test", port = fake.port))
        try {
            block(camera)
        } finally {
            camera.close()
        }
    }

    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `PropertyDesc parses a hand-built aperture enumeration`() {
        // code 0x5007, UINT16, writable, default 5.6, current 8, enum of 3: f/4 f/5.6 f/8
        val desc = PropertyDesc.parse(hex("07 50  04 00  01  30 02  20 03  02  03 00  90 01  30 02  20 03"))
        assertEquals(0x5007, desc.code)
        assertTrue(desc.writable)
        assertEquals(PropValue.Num(800), desc.current)
        assertEquals(listOf(400L, 560L, 800L), desc.choices.map { it.asLong })
    }

    @Test
    fun `signed INT16 values round-trip, including negative exposure compensation`() {
        assertArrayEquals(hex("B3 FE"), encodeValue(DataType.INT16, PropValue.Num(-333)))
        assertEquals(PropValue.Num(-333), PtpReader(hex("B3 FE")).value(DataType.INT16))
    }

    @Test
    fun `Nikon shutter speeds unpack numerator and denominator`() {
        assertEquals("1/250", NikonProps.shutter(PropValue.Num(0x000100FAL)))
        assertEquals("30\"", NikonProps.shutter(PropValue.Num(0x001E0001L)))
        assertEquals("Bulb", NikonProps.shutter(PropValue.Num(0xFFFFFFFFL)))
    }

    @Test
    fun `lists standard and vendor-only properties with names and formatted values`() = withCamera { camera ->
        val props = camera.properties().associateBy { it.code }

        // 0xD10B is only reachable through Nikon.GetVendorPropCodes, not DeviceInfo.
        assertEquals("Save remote shots to", props.getValue(0xD10B).name)
        assertEquals("f/5.6", props.getValue(0x5007).display)
        assertEquals("1/250", props.getValue(0xD100).display)
        assertEquals("A", props.getValue(0x500E).display)
        assertEquals("ISO 200", props.getValue(0x500F).display)
        // Named from libgphoto2's catalog, values labelled from its table for this property.
        assertEquals("Focus Mode 2", props.getValue(0xD161).name)
        assertEquals("AF-S", props.getValue(0xD161).display)
        // A code nobody has named (0xD1B5 on the real D5600) still shows up, labelled by its number.
        assertEquals("Property 0xD1B5", props.getValue(0xD1B5).name)
        // Values the real D5600 reported raw: 1800, 350, 2 and an unnamed MTP code.
        assertEquals("18 mm", props.getValue(0xD0E3).display)
        assertEquals("f/3.5", props.getValue(0xD0E5).display)
        assertEquals("Rotated 90° right", props.getValue(0xD10E).display)
        assertEquals("Device type", props.getValue(0xD407).name)
        assertEquals("Still camera", props.getValue(0xD407).display)
        assertTrue(Capability.Settings in camera.capabilities)
    }

    @Test
    fun `setting aperture and exposure compensation reaches the camera`() = withCamera { camera ->
        camera.setProperty(0x5007, PropValue.Num(800))
        camera.setProperty(0x5010, PropValue.Num(-666))

        assertEquals(PropValue.Num(800), fake.props.getValue(0x5007).current)
        assertEquals(PropValue.Num(-666), fake.props.getValue(0x5010).current)
        assertEquals("f/8", camera.property(0x5007)!!.display)
        assertEquals("−0.7", camera.property(0x5010)!!.display)
    }

    @Test
    fun `a value the camera rejects surfaces as an error and changes nothing`() = withCamera { camera ->
        val e = assertThrows(PtpResponseException::class.java) { runBlocking { camera.setProperty(0x5007, PropValue.Num(123)) } }
        assertEquals(Rc.InvalidParameter, e.responseCode)
        assertEquals(PropValue.Num(560), fake.props.getValue(0x5007).current)
    }

    @Test
    fun `clock sync writes the phone's time as a PTP date string`() = withCamera { camera ->
        assertTrue(Capability.ClockSync in camera.capabilities)
        camera.syncClock(LocalDateTime.of(2026, 11, 21, 14, 30, 5))
        assertEquals(PropValue.Str("20261121T143005"), fake.props.getValue(Prop.DateTime).current)
    }

    @Test
    fun `live view strips the Nikon header and scales tap-to-focus to the sensor area`() = withCamera { camera ->
        val lv = assertNotNull(camera.liveView).let { camera.liveView!! }
        assertTrue(Capability.TouchFocus in camera.capabilities)

        lv.start()
        assertTrue(fake.liveViewOn)
        val frame = lv.frame()
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3), frame.jpeg)

        lv.focusAt(0.25f, 0.5f)
        assertEquals(listOf(1500L, 2000L), fake.lastParams[NikonOp.ChangeAfArea])

        assertTrue(lv.autofocus())
        lv.capture()
        assertEquals(listOf(1L, 2L, 3L), camera.objectHandles())

        lv.stop()
        assertFalse(fake.liveViewOn)
    }

    @Test
    fun `capture asks for no autofocus and falls back to the standard command when Nikon's is refused`() = runBlocking {
        val refusing = FakePtpIpCamera(listOf(FakeObject(1, "DSC_0001.JPG", ByteArray(10))), refuseCaptureInMedia = true).start()
        try {
            val camera = CameraDrivers.open(PtpIpTransport.connect("127.0.0.1", ByteArray(16), "test", port = refusing.port))
            camera.capture()
            // 0xFFFFFFFF means "don't autofocus first"; 0xFFFFFFFE would autofocus and can be refused.
            assertEquals(listOf(0xFFFFFFFFL, 0L), refusing.lastParams[NikonOp.InitiateCaptureRecInMedia])
            assertEquals(1, refusing.operations.count { it == Op.InitiateCapture })
            assertEquals(listOf(1L, 2L), camera.objectHandles())
            // The working route is remembered: the next shot skips the refused command.
            camera.capture()
            assertEquals(1, refusing.operations.count { it == NikonOp.InitiateCaptureRecInMedia })
            camera.close()
        } finally {
            refusing.close()
        }
    }

    @Test
    fun `manual focus that runs into the end of the lens says focus is at its limit`() = withCamera { camera ->
        val lv = camera.liveView!!
        lv.start()
        fake.nextDeviceReady = 0xA00E // MfDriveStepInsufficiency, as the D5600 answers at infinity
        val error = runCatching { lv.manualFocus(400) }.exceptionOrNull()
        assertTrue(error is PtpException)
        assertEquals("Focus is at infinity", error?.message)
        assertEquals(listOf(2L, 400L), fake.lastParams[NikonOp.MfDrive])

        fake.nextDeviceReady = 0xA00C // MfDriveStepEnd
        val near = runCatching { lv.manualFocus(-40) }.exceptionOrNull()
        assertTrue(near is PtpException)
        assertEquals("Focus is at its nearest point", near?.message)
        assertEquals(listOf(1L, 40L), fake.lastParams[NikonOp.MfDrive])
    }

    @Test
    fun `manual focus, movie and bulb reach the camera`() = withCamera { camera ->
        val lv = camera.liveView!!
        assertTrue(Capability.ManualFocus in camera.capabilities)
        assertTrue(Capability.Movie in camera.capabilities)
        lv.start()

        lv.manualFocus(-30)
        assertEquals(listOf(1L, 30L), fake.lastParams[NikonOp.MfDrive])
        lv.manualFocus(5)
        assertEquals(listOf(2L, 5L), fake.lastParams[NikonOp.MfDrive])

        lv.startMovie()
        assertTrue(fake.movieOn)
        lv.stopMovie()
        assertFalse(fake.movieOn)

        lv.startBulb()
        lv.endBulb()
        assertEquals(listOf(0L, 0L), fake.lastParams[NikonOp.TerminateCapture])
    }

    @Test
    fun `delete removes the photo from the card`() = withCamera { camera ->
        camera.delete(1)
        assertEquals(listOf(2L), camera.objectHandles())
    }
}
