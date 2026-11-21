package app.viewfinder.ptp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The shot details a real camera writes into its JPEGs, for the fake camera's photos. */
data class FakeExif(
    val model: String,
    val focalMm: Int,
    val fNumber: Double,
    val exposureDenominator: Int,
    val iso: Int,
    val taken: String,
    /** Degrees north and east, or null for no location. */
    val location: Pair<Double, Double>? = null,
)

/** [jpeg] with an EXIF APP1 segment for [exif] right after its start marker. */
fun withExif(jpeg: ByteArray, exif: FakeExif): ByteArray {
    val tiff = tiff(exif)
    val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
    val out = ByteArrayOutputStream(jpeg.size + payload.size + 4)
    out.write(jpeg, 0, 2)
    out.write(0xFF); out.write(0xE1)
    val length = payload.size + 2
    out.write(length shr 8); out.write(length and 0xFF)
    out.write(payload)
    out.write(jpeg, 2, jpeg.size - 2)
    return out.toByteArray()
}

private class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

private const val ASCII = 2
private const val SHORT = 3
private const val LONG = 4
private const val RATIONAL = 5

private fun ascii(tag: Int, s: String) = (s + "\u0000").toByteArray(Charsets.US_ASCII).let { Entry(tag, ASCII, it.size, it) }
private fun short(tag: Int, v: Int) = Entry(tag, SHORT, 1, ByteBuffer.allocate(2).putShort(v.toShort()).array())
private fun long(tag: Int, v: Int) = Entry(tag, LONG, 1, ByteBuffer.allocate(4).putInt(v).array())
private fun rationals(tag: Int, vararg v: Pair<Int, Int>) =
    Entry(tag, RATIONAL, v.size, ByteBuffer.allocate(8 * v.size).apply { v.forEach { (n, d) -> putInt(n); putInt(d) } }.array())

private fun dms(deg: Double): Array<Pair<Int, Int>> {
    val d = deg.toInt()
    val m = ((deg - d) * 60).toInt()
    val s = ((deg - d - m / 60.0) * 3600 * 100).toInt()
    return arrayOf(d to 1, m to 1, s to 100)
}

/** A big-endian TIFF block: IFD0 (make, model, pointers), the Exif IFD and, with a location, the GPS IFD. */
private fun tiff(e: FakeExif): ByteArray {
    val exifIfd = listOf(
        rationals(0x829A, 1 to e.exposureDenominator),
        rationals(0x829D, (e.fNumber * 10).toInt() to 10),
        short(0x8827, e.iso),
        ascii(0x9003, e.taken),
        rationals(0x920A, e.focalMm * 10 to 10),
    )
    val gpsIfd = e.location?.let { (lat, lon) ->
        listOf(ascii(0x0001, "N"), rationals(0x0002, *dms(lat)), ascii(0x0003, "E"), rationals(0x0004, *dms(lon)))
    }
    fun ifd0(exifAt: Int, gpsAt: Int?) = listOfNotNull(
        ascii(0x010F, "NIKON CORPORATION"),
        ascii(0x0110, e.model),
        long(0x8769, exifAt),
        gpsAt?.let { long(0x8825, it) },
    )
    // Lay out IFD0, then Exif, then GPS; sizes don't depend on the pointer values, so measure first.
    val size0 = blockSize(ifd0(0, if (gpsIfd != null) 0 else null))
    val exifAt = 8 + size0
    val gpsAt = gpsIfd?.let { exifAt + blockSize(exifIfd) }
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8))
    out.write(block(ifd0(exifAt, gpsAt), 8))
    out.write(block(exifIfd, exifAt))
    if (gpsIfd != null) out.write(block(gpsIfd, gpsAt!!))
    return out.toByteArray()
}

private fun blockSize(entries: List<Entry>) = 2 + 12 * entries.size + 4 + entries.sumOf { if (it.value.size > 4) it.value.size + it.value.size % 2 else 0 }

/** One IFD at [at] (offset from the TIFF start), with its out-of-line values right after it. */
private fun block(entries: List<Entry>, at: Int): ByteArray {
    val sorted = entries.sortedBy { it.tag }
    val head = ByteBuffer.allocate(2 + 12 * sorted.size + 4)
    val data = ByteArrayOutputStream()
    var dataAt = at + head.capacity()
    head.putShort(sorted.size.toShort())
    for (entry in sorted) {
        head.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
        if (entry.value.size <= 4) {
            head.put(entry.value.copyOf(4))
        } else {
            head.putInt(dataAt)
            data.write(entry.value)
            if (entry.value.size % 2 == 1) data.write(0)
            dataAt += entry.value.size + entry.value.size % 2
        }
    }
    head.putInt(0)
    return head.array() + data.toByteArray()
}
