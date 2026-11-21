package app.viewfinder.ptp

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operations: Set<Int>,
    val events: Set<Int>,
    val properties: Set<Int>,
    val captureFormats: Set<Int>,
    val imageFormats: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = PtpReader(data)
            return DeviceInfo(
                standardVersion = r.u16(),
                vendorExtensionId = r.u32(),
                vendorExtensionVersion = r.u16(),
                vendorExtensionDesc = r.ptpString(),
                functionalMode = r.u16(),
                operations = r.u16Array().toSet(),
                events = r.u16Array().toSet(),
                properties = r.u16Array().toSet(),
                captureFormats = r.u16Array().toSet(),
                imageFormats = r.u16Array().toSet(),
                manufacturer = r.ptpString(),
                model = r.ptpString(),
                deviceVersion = r.ptpString(),
                serialNumber = r.ptpString(),
            )
        }
    }
}

data class StorageInfo(
    val id: Long,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpaceBytes: Long,
    /** 0xFFFFFFFF when the camera doesn't estimate it. */
    val freeSpaceImages: Long,
    val description: String,
    val volumeLabel: String,
) {
    companion object {
        fun parse(id: Long, data: ByteArray): StorageInfo {
            val r = PtpReader(data)
            return StorageInfo(
                id = id,
                storageType = r.u16(),
                filesystemType = r.u16(),
                accessCapability = r.u16(),
                maxCapacity = r.u64(),
                freeSpaceBytes = r.u64(),
                freeSpaceImages = r.u32(),
                description = r.ptpString(),
                volumeLabel = r.ptpString(),
            )
        }
    }
}

data class ObjectInfo(
    val handle: Long,
    val storageId: Long,
    val format: Int,
    val protectionStatus: Int,
    /** 0xFFFFFFFF for objects of 4 GiB or more. */
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val thumbWidth: Long,
    val thumbHeight: Long,
    val imageWidth: Long,
    val imageHeight: Long,
    val imageBitDepth: Long,
    val parent: Long,
    val associationType: Int,
    val associationDesc: Long,
    val sequenceNumber: Long,
    val filename: String,
    val captureDate: LocalDateTime?,
    val modificationDate: LocalDateTime?,
    val keywords: String,
) {
    val isFolder: Boolean get() = format == Format.Association

    companion object {
        fun parse(handle: Long, data: ByteArray): ObjectInfo {
            val r = PtpReader(data)
            return ObjectInfo(
                handle = handle,
                storageId = r.u32(),
                format = r.u16(),
                protectionStatus = r.u16(),
                compressedSize = r.u32(),
                thumbFormat = r.u16(),
                thumbCompressedSize = r.u32(),
                thumbWidth = r.u32(),
                thumbHeight = r.u32(),
                imageWidth = r.u32(),
                imageHeight = r.u32(),
                imageBitDepth = r.u32(),
                parent = r.u32(),
                associationType = r.u16(),
                associationDesc = r.u32(),
                sequenceNumber = r.u32(),
                filename = r.ptpString(),
                captureDate = parsePtpDate(r.ptpString()),
                modificationDate = parsePtpDate(r.ptpString()),
                keywords = r.ptpString(),
            )
        }
    }
}

private val ptpDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** PTP dates are "YYYYMMDDThhmmss", optionally followed by ".s" tenths and a zone; the camera's local time is kept. */
fun parsePtpDate(s: String): LocalDateTime? {
    if (s.length < 15) return null
    return runCatching { LocalDateTime.parse(s.substring(0, 15), ptpDate) }.getOrNull()
}
