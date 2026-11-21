package app.viewfinder.ptp

import java.io.ByteArrayOutputStream

/**
 * Little-endian writer for PTP datasets and PTP/IP packets.
 * Unsigned 32-bit values are carried as [Long] so handles above 0x7FFFFFFF stay positive.
 */
class PtpWriter(initialCapacity: Int = 64) {
    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = apply {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun u32(v: Long) = apply {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun u64(v: Long) = apply {
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun bytes(b: ByteArray) = apply { out.write(b) }

    /** PTP string: a u8 count of UTF-16 units including the NUL, then UTF-16LE. Empty is a single 0. */
    fun ptpString(s: String) = apply {
        if (s.isEmpty()) {
            u8(0)
        } else {
            require(s.length < 255) { "PTP strings hold at most 254 characters" }
            u8(s.length + 1)
            s.forEach { u16(it.code) }
            u16(0)
        }
    }

    /** NUL-terminated UTF-16LE with no length prefix, as used by PTP/IP init packets. */
    fun utf16z(s: String) = apply {
        s.forEach { u16(it.code) }
        u16(0)
    }

    fun u16Array(values: List<Int>) = apply {
        u32(values.size.toLong())
        values.forEach { u16(it) }
    }

    fun u32Array(values: List<Long>) = apply {
        u32(values.size.toLong())
        values.forEach { u32(it) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Little-endian reader over a PTP dataset. Throws [PtpException] on truncated input. */
class PtpReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw PtpException("Truncated PTP data: needed $n bytes, $remaining left")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return buf.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun ptpString(): String {
        val units = u8()
        if (units == 0) return ""
        need(units * 2)
        val sb = StringBuilder(units)
        var ended = false
        repeat(units) {
            val c = u16()
            if (c == 0) ended = true
            if (!ended) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun utf16z(): String {
        val sb = StringBuilder()
        while (true) {
            val c = u16()
            if (c == 0) return sb.toString()
            sb.append(c.toChar())
        }
    }

    fun u16Array(): List<Int> {
        val n = count(2)
        return List(n) { u16() }
    }

    fun u32Array(): List<Long> {
        val n = count(4)
        return List(n) { u32() }
    }

    private fun count(elementSize: Int): Int {
        val n = u32()
        if (n > remaining / elementSize) throw PtpException("PTP array claims $n elements, only ${remaining / elementSize} fit")
        return n.toInt()
    }
}

fun hex16(v: Int): String = "0x%04X".format(v)
fun hex32(v: Long): String = "0x%08X".format(v)
