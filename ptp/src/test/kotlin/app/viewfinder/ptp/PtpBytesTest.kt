package app.viewfinder.ptp

import app.viewfinder.ptp.ptpip.DataPhase
import app.viewfinder.ptp.ptpip.PtpIpPackets
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Byte layouts are written out by hand from ISO 15740 / CIPA DC-005, not produced by the code under test. */
class PtpBytesTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `InitCommandRequest is length, type, GUID, UTF-16 name, protocol version 1_0`() {
        val guid = ByteArray(16) { it.toByte() }
        val expected = hex(
            "20 00 00 00  01 00 00 00" +
                " 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F" +
                " 41 00 00 00" +
                " 00 00 01 00",
        )
        assertArrayEquals(expected, PtpIpPackets.initCommandRequest(guid, "A"))
    }

    @Test
    fun `OpenSession request carries data phase, opcode, transaction id and session id`() {
        val expected = hex("16 00 00 00  06 00 00 00  01 00 00 00  02 10  00 00 00 00  01 00 00 00")
        assertArrayEquals(expected, PtpIpPackets.operationRequest(DataPhase.NoneOrIn, Op.OpenSession, 0, listOf(1)))
    }

    @Test
    fun `StartData announces a 64-bit length`() {
        val expected = hex("14 00 00 00  09 00 00 00  07 00 00 00  00 00 00 00 01 00 00 00")
        assertArrayEquals(expected, PtpIpPackets.startData(7, 1L shl 32))
    }

    @Test
    fun `ObjectInfo parses a hand-built D5600 JPEG record`() {
        val name = "DSC_0001.JPG"
        val date = "20261121T143005"
        fun str(s: String) = byteArrayOf((s.length + 1).toByte()) +
            s.flatMap { listOf(it.code.toByte(), 0) }.toByteArray() + byteArrayOf(0, 0)
        val bytes = hex(
            "01 00 01 00  01 38  00 00  80 8D 5B 00  08 38  00 10 00 00" +
                " A0 00 00 00  78 00 00 00  70 17 00 00  A0 0F 00 00  18 00 00 00" +
                " 05 00 00 00  00 00  00 00 00 00  00 00 00 00",
        ) + str(name) + str(date) + byteArrayOf(0) + byteArrayOf(0)

        val info = ObjectInfo.parse(0x80000001L, bytes)

        assertEquals(0x80000001L, info.handle)
        assertEquals(0x00010001L, info.storageId)
        assertEquals(Format.ExifJpeg, info.format)
        assertEquals(6_000_000L, info.compressedSize)
        assertEquals(6000L, info.imageWidth)
        assertEquals(4000L, info.imageHeight)
        assertEquals(5L, info.parent)
        assertEquals("DSC_0001.JPG", info.filename)
        assertEquals(LocalDateTime.of(2026, 11, 21, 14, 30, 5), info.captureDate)
        assertNull(info.modificationDate)
    }

    @Test
    fun `u32 above 0x7FFFFFFF stays positive`() {
        assertEquals(4_294_967_295L, PtpReader(hex("FF FF FF FF")).u32())
    }

    @Test
    fun `PTP dates keep the camera's local time and ignore tenths and zone`() {
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), parsePtpDate("20260102T030405.0+0300"))
        assertNull(parsePtpDate(""))
    }

    @Test
    fun `an array longer than the data is rejected instead of over-allocating`() {
        assertThrows(PtpException::class.java) { PtpReader(hex("FF FF 00 00  01 00")).u16Array() }
    }
}
