package app.viewfinder

import kotlin.math.roundToInt

/*
 * What a share sends and how, as plain values and pure functions (no Android), so the rules are unit-tested.
 * The Android side (decoding, encoding, metadata, intents) is in Sharer.
 */

enum class ShareFormat(val label: String, val bulkOnly: Boolean = false) {
    /** The files as they came off the card. */
    Original("Original"),
    Jpeg("JPEG"),
    Heif("HEIF"),
    Webp("WebP"),
    /** All originals in one file. */
    Zip("ZIP", bulkOnly = true),
    /** One PDF with a grid of previews. */
    ContactSheet("Contact sheet", bulkOnly = true),
    ;

    /** Re-encoded into a new image: size, quality, crop and card apply. */
    val rendered: Boolean get() = this == Jpeg || this == Heif || this == Webp
}

enum class ShareSize(val label: String, val longEdge: Int?) { Full("Full", null), Large("2048 px", 2048), Small("1080 px", 1080) }

enum class ShareQuality(val label: String, val jpeg: Int, val webp: Int, val heif: Int) {
    High("High", jpeg = 92, webp = 90, heif = 90),
    Smaller("Smaller", jpeg = 80, webp = 78, heif = 75),
}

/** Fixed frames for social apps; [ratio] is width over height. */
enum class ShareCrop(val label: String, val ratio: Float?) {
    AsShot("As shot", null),
    Portrait("4:5", 4f / 5f),
    Square("1:1", 1f),
    Story("9:16", 9f / 16f),
}

data class ShareOptions(
    val singleFormat: ShareFormat = ShareFormat.Original,
    val bulkFormat: ShareFormat = ShareFormat.Original,
    val size: ShareSize = ShareSize.Full,
    val quality: ShareQuality = ShareQuality.High,
    val crop: ShareCrop = ShareCrop.AsShot,
    val card: Boolean = false,
    val removeLocation: Boolean = true,
    val removeDetails: Boolean = false,
) {
    /** One photo and a selection remember their format apart, so a single photo never inherits ZIP. */
    fun formatFor(count: Int): ShareFormat = if (count > 1) bulkFormat else singleFormat.takeUnless { it.bulkOnly } ?: ShareFormat.Original

    fun withFormat(count: Int, format: ShareFormat): ShareOptions = if (count > 1) copy(bulkFormat = format) else copy(singleFormat = format)
}

/** One file a share sends: as is ([render] false) or re-encoded. */
data class ShareFile(val item: PhotoItem, val render: Boolean)

data class SharePlan(val files: List<ShareFile>, val skippedRaw: Int)

/**
 * Which files go out for [tiles] (gallery tiles, a RAW+JPEG pair being one tile) in [format]:
 * - Original and ZIP send every file, both halves of a pair included.
 * - JPEG, HEIF and WebP re-encode the JPEG of each tile; RAW-only tiles can't be decoded, so they're skipped
 *   and counted. Videos always go as original files.
 * - The contact sheet lists each tile once (from the camera's previews, never downloaded).
 */
fun planShare(tiles: List<PhotoItem>, format: ShareFormat): SharePlan {
    var skipped = 0
    val files = buildList {
        for (tile in tiles) {
            val single = tile.copy(raw = null)
            when (format) {
                ShareFormat.Original, ShareFormat.Zip -> {
                    add(ShareFile(single, render = false))
                    tile.raw?.let { add(ShareFile(it, render = false)) }
                }
                ShareFormat.ContactSheet -> add(ShareFile(single, render = false))
                ShareFormat.Jpeg, ShareFormat.Heif, ShareFormat.Webp -> when (tile.kind) {
                    MediaKind.Jpeg -> add(ShareFile(single, render = true))
                    MediaKind.Video -> add(ShareFile(single, render = false))
                    MediaKind.Raw, MediaKind.Other -> skipped++
                }
            }
        }
    }
    return SharePlan(files, skipped)
}

/** A rectangle in pixels. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The largest [ratio] (width / height) frame inside a [width] × [height] image. [focusX] and [focusY]
 * (0..1) slide it within the room left over: 0.5 centres it. Null ratio keeps the whole image.
 */
fun cropBox(width: Int, height: Int, ratio: Float?, focusX: Float = 0.5f, focusY: Float = 0.5f): Box {
    if (ratio == null) return Box(0, 0, width, height)
    val (w, h) = if (width.toFloat() / height > ratio) (height * ratio).roundToInt() to height else width to (width / ratio).roundToInt()
    val left = ((width - w) * focusX.coerceIn(0f, 1f)).roundToInt()
    val top = ((height - h) * focusY.coerceIn(0f, 1f)).roundToInt()
    return Box(left, top, w.coerceAtMost(width), h.coerceAtMost(height))
}

/** [width] × [height] shrunk so the long edge is at most [longEdge]; never enlarged. */
fun fitSize(width: Int, height: Int, longEdge: Int?): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (longEdge == null || long <= longEdge) return width to height
    val scale = longEdge.toFloat() / long
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** The photo card's caption, e.g. "D5600 · 35 mm · f/1.8 · 1/250 s · ISO 200"; missing values are left out. */
fun cardCaption(model: String?, focalMm: Double?, fNumber: Double?, exposureSec: Double?, iso: Int?): String =
    listOfNotNull(
        model?.trim()?.removePrefix("NIKON ")?.takeIf { it.isNotEmpty() },
        focalMm?.takeIf { it > 0 }?.let { "${trimNumber(it)} mm" },
        fNumber?.takeIf { it > 0 }?.let { "f/${trimNumber(it)}" },
        exposureSec?.takeIf { it > 0 }?.let(::exposureLabel),
        iso?.takeIf { it > 0 }?.let { "ISO $it" },
    ).joinToString(" · ")

/** 0.004 → "1/250 s", 0.5 → "1/2 s", 2.0 → "2 s", 1.3 → "1.3 s". */
fun exposureLabel(seconds: Double): String =
    if (seconds < 1) "1/${(1 / seconds).roundToInt()} s" else "${trimNumber(seconds)} s"

/** 8.0 → "8", 5.6 → "5.6": numbers as a camera prints them. */
fun trimNumber(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/** "photo" or "photos", for counts in messages and buttons. */
fun photos(n: Int): String = if (n == 1) "1 photo" else "$n photos"

/**
 * A JPEG without its metadata segments (EXIF and XMP in APP1, Photoshop/IPTC in APP13, comments), every
 * other byte kept as is: colour profile (APP2), tables and the compressed image itself. Not a JPEG → unchanged.
 */
fun stripJpegMetadata(jpeg: ByteArray): ByteArray {
    if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
    val out = java.io.ByteArrayOutputStream(jpeg.size)
    out.write(jpeg, 0, 2)
    var i = 2
    while (i + 1 < jpeg.size) {
        if (jpeg[i] != 0xFF.toByte()) return jpeg   // lost sync: leave the file alone rather than damage it
        val marker = jpeg[i + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> { i++; continue }   // fill byte
            marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01 -> { out.write(jpeg, i, 2); i += 2; continue }
        }
        if (i + 3 >= jpeg.size) return jpeg
        val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
        val end = i + 2 + length
        if (length < 2 || end > jpeg.size) return jpeg
        if (marker == 0xDA) {   // start of scan: the image data runs to the end
            out.write(jpeg, i, jpeg.size - i)
            return out.toByteArray()
        }
        if (marker != 0xE1 && marker != 0xED && marker != 0xFE) out.write(jpeg, i, end - i)
        i = end
    }
    return out.toByteArray()
}
