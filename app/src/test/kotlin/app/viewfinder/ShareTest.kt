package app.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTest {

    private fun item(handle: Long, name: String, kind: MediaKind, raw: PhotoItem? = null) =
        PhotoItem(handle, name, kind, size = 1000, captured = null, width = 6000, height = 4000, saved = false, raw = raw)

    private val nef = item(2, "DSC_0001.NEF", MediaKind.Raw)
    private val pair = item(1, "DSC_0001.JPG", MediaKind.Jpeg, raw = nef)
    private val jpeg = item(3, "DSC_0002.JPG", MediaKind.Jpeg)
    private val rawOnly = item(4, "DSC_0003.NEF", MediaKind.Raw)
    private val video = item(5, "DSC_0004.MOV", MediaKind.Video)
    private val tiles = listOf(pair, jpeg, rawOnly, video)

    private fun SharePlan.summary() = files.map { "${it.item.name}${if (it.render) "*" else ""}" }

    @Test
    fun `original sends every file, both halves of a pair`() {
        val plan = planShare(tiles, ShareFormat.Original)
        assertEquals(listOf("DSC_0001.JPG", "DSC_0001.NEF", "DSC_0002.JPG", "DSC_0003.NEF", "DSC_0004.MOV"), plan.summary())
        assertEquals(0, plan.skippedRaw)
    }

    @Test
    fun `zip packs the same files as original`() {
        assertEquals(planShare(tiles, ShareFormat.Original).summary(), planShare(tiles, ShareFormat.Zip).summary())
        assertEquals(5, planShare(tiles, ShareFormat.Zip).files.size)
    }

    @Test
    fun `converted formats re-encode JPEGs, pass videos through and skip RAW-only tiles`() {
        for (format in listOf(ShareFormat.Jpeg, ShareFormat.Heif, ShareFormat.Webp)) {
            val plan = planShare(tiles, format)
            assertEquals(listOf("DSC_0001.JPG*", "DSC_0002.JPG*", "DSC_0004.MOV"), plan.summary())
            assertEquals(1, plan.skippedRaw)
        }
    }

    @Test
    fun `a pair's RAW is not downloaded for a converted share`() {
        val handles = planShare(listOf(pair), ShareFormat.Jpeg).files.map { it.item.handle }
        assertEquals(listOf(1L), handles)
        assertEquals(listOf(1L, 2L), planShare(listOf(pair), ShareFormat.Original).files.map { it.item.handle })
    }

    @Test
    fun `contact sheet lists each tile once`() {
        val plan = planShare(tiles, ShareFormat.ContactSheet)
        assertEquals(listOf("DSC_0001.JPG", "DSC_0002.JPG", "DSC_0003.NEF", "DSC_0004.MOV"), plan.summary())
    }

    @Test
    fun `a single photo never inherits a bulk-only format`() {
        val options = ShareOptions(singleFormat = ShareFormat.Heif, bulkFormat = ShareFormat.Zip)
        assertEquals(ShareFormat.Heif, options.formatFor(1))
        assertEquals(ShareFormat.Zip, options.formatFor(12))
        assertEquals(ShareFormat.Original, ShareOptions(singleFormat = ShareFormat.Zip).formatFor(1))
        assertEquals(ShareFormat.Webp, options.withFormat(1, ShareFormat.Webp).formatFor(1))
        assertEquals(ShareFormat.Zip, options.withFormat(1, ShareFormat.Webp).formatFor(3))
    }

    @Test
    fun `crop takes the largest frame and slides it by the focus`() {
        assertEquals(Box(0, 0, 6000, 4000), cropBox(6000, 4000, null))
        assertEquals(Box(1000, 0, 4000, 4000), cropBox(6000, 4000, 1f))
        assertEquals(Box(0, 0, 4000, 4000), cropBox(6000, 4000, 1f, focusX = 0f))
        assertEquals(Box(2000, 0, 4000, 4000), cropBox(6000, 4000, 1f, focusX = 1f))
        assertEquals(Box(1400, 0, 3200, 4000), cropBox(6000, 4000, 4f / 5f))
        // A portrait photo is taller than 4:5, so the frame keeps the width.
        assertEquals(Box(0, 250, 4000, 5000), cropBox(4000, 5500, 4f / 5f))
    }

    @Test
    fun `fit shrinks the long edge and never enlarges`() {
        assertEquals(2048 to 1365, fitSize(6000, 4000, 2048))
        assertEquals(720 to 1080, fitSize(4000, 6000, 1080))
        assertEquals(800 to 600, fitSize(800, 600, 2048))
        assertEquals(6000 to 4000, fitSize(6000, 4000, null))
    }

    @Test
    fun `card caption reads like a camera's info line`() {
        assertEquals("D5600 · 35 mm · f/1.8 · 1/250 s · ISO 200", cardCaption("NIKON D5600", 35.0, 1.8, 0.004, 200))
        assertEquals("D5600 · 18 mm · f/3.5", cardCaption("NIKON D5600", 18.0, 3.5, null, null))
        assertEquals("2 s · ISO 100", cardCaption(null, null, null, 2.0, 100))
        assertEquals("1.3 s", exposureLabel(1.3))
        assertEquals("1/2 s", exposureLabel(0.5))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun segment(marker: Int, vararg payload: Int) = bytes(0xFF, marker, 0, payload.size + 2, *payload)

    @Test
    fun `stripping drops EXIF, XMP, IPTC and comments but keeps the profile, tables and image`() {
        val soi = bytes(0xFF, 0xD8)
        val app0 = segment(0xE0, 0x4A, 0x46)          // JFIF
        val exif = segment(0xE1, 0x45, 0x78, 0x69)   // EXIF: carries the location
        val icc = segment(0xE2, 0x49, 0x43)          // colour profile
        val iptc = segment(0xED, 0x50)
        val comment = segment(0xFE, 0x68, 0x69)
        val dqt = segment(0xDB, 0x00, 0x01)
        val scan = bytes(0xFF, 0xDA, 0, 4, 0x01, 0x02, 0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD9)
        val jpeg = soi + app0 + exif + icc + iptc + comment + dqt + scan
        assertEquals((soi + app0 + icc + dqt + scan).toList(), stripJpegMetadata(jpeg).toList())
    }

    @Test
    fun `stripping leaves anything that isn't a clean JPEG untouched`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertEquals(png.toList(), stripJpegMetadata(png).toList())
        val truncated = bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x40, 0x01)   // length runs past the end
        assertEquals(truncated.toList(), stripJpegMetadata(truncated).toList())
    }
}
