package app.viewfinder.ptp

/** PTP data type codes used by device properties. */
object DataType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val INT128 = 0x0009
    const val UINT128 = 0x000A
    const val ARRAY_FLAG = 0x4000
    const val STR = 0xFFFF
}

/** A property value: a number (signed per its data type), a string or an array of numbers. */
sealed interface PropValue {
    data class Num(val value: Long) : PropValue
    data class Str(val value: String) : PropValue
    data class Arr(val values: List<Long>) : PropValue

    val asLong: Long? get() = (this as? Num)?.value
}

sealed interface PropForm {
    data object None : PropForm
    data class Range(val min: PropValue, val max: PropValue, val step: PropValue) : PropForm
    data class Enumeration(val values: List<PropValue>) : PropForm
}

data class PropertyDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val factoryDefault: PropValue,
    val current: PropValue,
    val form: PropForm,
) {
    /** Every value the camera accepts right now, for pickers. Empty when it is free-form. */
    val choices: List<PropValue>
        get() = when (val f = form) {
            is PropForm.Enumeration -> f.values
            is PropForm.Range -> {
                val min = f.min.asLong
                val max = f.max.asLong
                val step = f.step.asLong
                if (min == null || max == null || step == null || step <= 0 || (max - min) / step > 400) emptyList()
                else (min..max step step).map { PropValue.Num(it) }
            }
            PropForm.None -> emptyList()
        }

    companion object {
        fun parse(data: ByteArray): PropertyDesc {
            val r = PtpReader(data)
            val code = r.u16()
            val type = r.u16()
            val writable = r.u8() == 1
            val factory = r.value(type)
            val current = r.value(type)
            val form = when (if (r.remaining > 0) r.u8() else 0) {
                1 -> PropForm.Range(r.value(type), r.value(type), r.value(type))
                2 -> PropForm.Enumeration(List(r.u16()) { r.value(type) })
                else -> PropForm.None
            }
            return PropertyDesc(code, type, writable, factory, current, form)
        }
    }
}

/** Reads one value of a PTP data type; signed types come back sign-extended. */
fun PtpReader.value(type: Int): PropValue = when {
    type == DataType.STR -> PropValue.Str(ptpString())
    type and DataType.ARRAY_FLAG != 0 -> {
        val element = type and DataType.ARRAY_FLAG.inv()
        PropValue.Arr(List(u32().toInt().coerceAtMost(remaining)) { (scalar(element) as PropValue.Num).value })
    }
    else -> scalar(type)
}

private fun PtpReader.scalar(type: Int): PropValue = PropValue.Num(
    when (type) {
        DataType.INT8 -> u8().toByte().toLong()
        DataType.UINT8 -> u8().toLong()
        DataType.INT16 -> u16().toShort().toLong()
        DataType.UINT16 -> u16().toLong()
        DataType.INT32 -> u32().toInt().toLong()
        DataType.UINT32 -> u32()
        DataType.INT64, DataType.UINT64 -> u64()
        DataType.INT128, DataType.UINT128 -> u64().also { u64() } // low half is plenty for any real property
        else -> throw PtpException("Unknown PTP data type ${hex16(type)}")
    },
)

/** Encodes a value for SetDevicePropValue. */
fun encodeValue(type: Int, value: PropValue): ByteArray {
    val w = PtpWriter()
    when {
        type == DataType.STR -> w.ptpString((value as PropValue.Str).value)
        type and DataType.ARRAY_FLAG != 0 -> {
            val values = (value as PropValue.Arr).values
            w.u32(values.size.toLong())
            values.forEach { w.scalar(type and DataType.ARRAY_FLAG.inv(), it) }
        }
        else -> w.scalar(type, (value as PropValue.Num).value)
    }
    return w.toByteArray()
}

private fun PtpWriter.scalar(type: Int, v: Long) {
    when (type) {
        DataType.INT8, DataType.UINT8 -> u8(v.toInt())
        DataType.INT16, DataType.UINT16 -> u16(v.toInt())
        DataType.INT32, DataType.UINT32 -> u32(v)
        DataType.INT64, DataType.UINT64 -> u64(v)
        DataType.INT128, DataType.UINT128 -> u64(v).u64(if (v < 0 && type == DataType.INT128) -1 else 0)
        else -> throw PtpException("Unknown PTP data type ${hex16(type)}")
    }
}
