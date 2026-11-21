package app.viewfinder

import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** A file ready to hand to another app. */
data class Outgoing(val uri: Uri, val mime: String, val name: String)

/** The app a share went to last time, for the panel's "Last used" chip. */
data class ShareApp(val component: ComponentName, val label: String, val icon: Drawable)

/** One cell of a contact sheet: the camera's preview of a photo, its file name and a caption. */
class SheetEntry(val name: String, val caption: String, val image: ByteArray?)

/**
 * The Android side of sharing: makes the copies a share needs (re-encoded, cropped, carded, cleaned of
 * location), packs ZIPs and contact sheets, and builds the intents. Copies live in `cache/share/` and are
 * handed out through a FileProvider; [cleanUp] removes old ones.
 */
class Sharer(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val root get() = File(context.cacheDir, "share")

    /** Whether this phone can encode HEIF (needs a hardware or platform HEVC/HEIC encoder). */
    val heifSupported: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(MIME_HEIC, true) || it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
            }
        }.getOrDefault(false)
    }

    fun cleanUp(olderThanMs: Long = DAY_MS) {
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { now - it.lastModified() > olderThanMs }?.forEach { it.deleteRecursively() }
    }

    /**
     * An original file, as untouched as privacy allows. JPEGs are cleaned in a copy (metadata rewritten,
     * pixels untouched). RAW, HEIF and video can be read but not rewritten: with location removal on, one that
     * carries a location (or can't be checked) comes back null and is left out.
     */
    suspend fun original(source: Uri, name: String, options: ShareOptions): Outgoing? = withContext(Dispatchers.IO) {
        val mime = MediaSaver.mimeFor(name)
        val located = options.removeLocation && hasLocation(source, mime)
        when {
            mime == MIME_JPEG && (options.removeDetails || located) -> Outgoing(cleanJpeg(source, name, options), mime, name)
            located -> null
            else -> Outgoing(source, mime, name)
        }
    }

    /**
     * A re-encoded copy: cropped by [ShareOptions.crop] (slid by [focusX]/[focusY]), shrunk to the size, framed
     * by the photo card if chosen, in [format]. Camera details are copied back unless removed (HEIF copies
     * carry none), always without location when location removal is on.
     */
    suspend fun render(source: Uri, name: String, options: ShareOptions, format: ShareFormat, focusX: Float = 0.5f, focusY: Float = 0.5f): Outgoing =
        withContext(Dispatchers.IO) {
            val exif = readExif(source)
            val image = decode(source, options, focusX, focusY)
            coroutineContext.ensureActive()
            val out = if (options.card) card(image, exif).also { image.recycle() } else image
            val (ext, mime) = when (format) {
                ShareFormat.Heif -> "heic" to "image/heic"
                ShareFormat.Webp -> "webp" to "image/webp"
                else -> "jpg" to MIME_JPEG
            }
            val file = newFile("${name.substringBeforeLast('.')}.$ext")
            try {
                when (format) {
                    ShareFormat.Heif -> writeHeif(out, file, options.quality.heif)
                    ShareFormat.Webp -> file.outputStream().buffered().use {
                        @Suppress("DEPRECATION")
                        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        out.compress(webp, options.quality.webp, it)
                    }
                    else -> file.outputStream().buffered().use { out.compress(Bitmap.CompressFormat.JPEG, options.quality.jpeg, it) }
                }
            } finally {
                out.recycle()
            }
            if (format != ShareFormat.Heif && !options.removeDetails && exif != null) copyExif(exif, file, keepLocation = !options.removeLocation)
            Outgoing(uriFor(file), mime, file.name)
        }

    /** All [files] in one ZIP, stored without recompression (photos don't shrink). */
    suspend fun zip(files: List<Outgoing>, title: String): Outgoing = withContext(Dispatchers.IO) {
        val file = newFile("$title.zip")
        val used = mutableSetOf<String>()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (f in files) {
                coroutineContext.ensureActive()
                zip.putNextEntry(ZipEntry(uniqueName(f.name, used)))
                (resolver.openInputStream(f.uri) ?: throw IOException("Couldn't read ${f.name}")).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Outgoing(uriFor(file), "application/zip", file.name)
    }

    /** An A4 PDF: [title] on top, then a three-column grid of previews, each with its name and caption. */
    suspend fun contactSheet(entries: List<SheetEntry>, title: String, subtitle: String): Outgoing = withContext(Dispatchers.IO) {
        val file = newFile("$title.pdf")
        val pdf = PdfDocument()
        val inter = ResourcesCompat.getFont(context, R.font.inter)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = inter; textSize = 15f; color = INK }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = inter; textSize = 8.5f; color = INK_SOFT }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = inter; textSize = 7.5f; color = INK }
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = inter; textSize = 6.5f; color = INK_SOFT }
        val wellPaint = Paint().apply { color = WELL }
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cellW = (A4_W - 2 * MARGIN - (COLUMNS - 1) * GAP) / COLUMNS
        val imageH = cellW * 0.72f
        val rowH = imageH + 30f
        var page: PdfDocument.Page? = null
        var y = 0f
        var col = 0
        var pageNo = 0
        try {
            for (entry in entries) {
                coroutineContext.ensureActive()
                if (page == null || (col == 0 && y + rowH > A4_H - MARGIN)) {
                    page?.let(pdf::finishPage)
                    pageNo++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W.toInt(), A4_H.toInt(), pageNo).create())
                    y = MARGIN
                    if (pageNo == 1) {
                        page.canvas.drawText(title, MARGIN, y + 14f, titlePaint)
                        page.canvas.drawText(subtitle, MARGIN, y + 28f, subPaint)
                        y += 46f
                    }
                    col = 0
                }
                val canvas = page.canvas
                val x = MARGIN + col * (cellW + GAP)
                val well = RectF(x, y, x + cellW, y + imageH)
                canvas.drawRect(well, wellPaint)
                entry.image?.let { decodeSmall(it, SHEET_PX) }?.let { bmp ->
                    val scale = minOf(well.width() / bmp.width, well.height() / bmp.height)
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    canvas.drawBitmap(bmp, null, RectF(well.centerX() - w / 2, well.centerY() - h / 2, well.centerX() + w / 2, well.centerY() + h / 2), imagePaint)
                    bmp.recycle()
                }
                canvas.drawText(ellipsize(entry.name, namePaint, cellW), x, y + imageH + 12f, namePaint)
                canvas.drawText(ellipsize(entry.caption, capPaint, cellW), x, y + imageH + 22f, capPaint)
                col++
                if (col == COLUMNS) {
                    col = 0
                    y += rowH + GAP
                }
            }
            page?.let(pdf::finishPage)
            file.outputStream().buffered().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        Outgoing(uriFor(file), "application/pdf", file.name)
    }

    /** The caption for a contact-sheet cell: the shot's settings when the preview carries them. */
    fun sheetCaption(preview: ByteArray?, fallback: String): String {
        val exif = preview?.let { runCatching { ExifInterface(ByteArrayInputStream(it)) }.getOrNull() } ?: return fallback
        return captionOf(exif, withModel = false).ifEmpty { fallback }
    }

    /** The photo card's caption for a file on the phone (camera, lens, settings), empty when it has none. */
    fun captionOf(uri: Uri): String = readExif(uri)?.let { captionOf(it, withModel = true) }.orEmpty()

    /** The send intent for [files]: one or many streams, read access granted, the narrowest common type. */
    fun sendIntent(files: List<Outgoing>): Intent {
        val uris = files.map { it.uri }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = commonMime(files.map { it.mime })
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Android's share sheet for [send]; the app picked is reported to [ShareChosenReceiver]. */
    fun chooser(send: Intent, title: String): Intent {
        val chosen = PendingIntent.getBroadcast(
            context, 0, Intent(context, ShareChosenReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Intent.createChooser(send, title, chosen.intentSender)
    }

    /** The last-used app, if it's still installed and still takes shares. */
    fun lastApp(flattened: String?): ShareApp? {
        val component = flattened?.let(ComponentName::unflattenFromString) ?: return null
        return try {
            val info = context.packageManager.getActivityInfo(component, 0)
            ShareApp(component, info.loadLabel(context.packageManager).toString(), info.loadIcon(context.packageManager))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    // region Images

    private fun decode(source: Uri, options: ShareOptions, focusX: Float, focusY: Float): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // The size here is already upright (orientation applied); crop in full-size pixels, then scale.
            val box = cropBox(info.size.width, info.size.height, options.crop.ratio, focusX, focusY)
            val (fw, _) = fitSize(box.width, box.height, options.size.longEdge)
            val scale = fw.toFloat() / box.width
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            if (options.crop.ratio != null || scale < 1f) {
                decoder.crop = Rect(
                    (box.left * scale).toInt(), (box.top * scale).toInt(),
                    ((box.left + box.width) * scale).toInt(), ((box.top + box.height) * scale).toInt(),
                )
            }
        }

    /** The photo card: an off-white border, with the shot's details on a deeper bottom band. */
    private fun card(image: Bitmap, exif: ExifInterface?): Bitmap {
        val short = minOf(image.width, image.height)
        val border = (short * 0.035f).toInt().coerceAtLeast(8)
        val caption = exif?.let { captionOf(it, withModel = true) }.orEmpty()
        val band = if (caption.isEmpty()) border else (short * 0.11f).toInt()
        val out = Bitmap.createBitmap(image.width + border * 2, image.height + border + band, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(PAPER)
        canvas.drawBitmap(image, border.toFloat(), border.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (caption.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = ResourcesCompat.getFont(context, R.font.inter)
                textSize = short * 0.028f
                color = INK_SOFT
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.02f
            }
            val baseline = image.height + border + band / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(caption, out.width / 2f, baseline, paint)
        }
        return out
    }

    private fun writeHeif(bitmap: Bitmap, file: File, quality: Int) {
        val writer = HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .setMaxImages(1)
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(HEIF_TIMEOUT_MS)
        } finally {
            writer.close()
        }
    }

    private fun decodeSmall(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // endregion

    // region Metadata

    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.getOrNull()

    /** True when the file carries a location, or when it can't be checked (so it's treated as if it did). */
    private fun hasLocation(uri: Uri, mime: String): Boolean = when {
        mime.startsWith("video/") -> runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null
                } finally {
                    release()
                }
            }
        }.getOrDefault(true)
        else -> readExif(uri)?.let { it.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null || it.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null } ?: true
    }

    /** A JPEG original's copy without location (and without any camera details if asked), pixels untouched. */
    private fun cleanJpeg(source: Uri, name: String, options: ShareOptions): Uri {
        val bytes = resolver.openInputStream(source)?.use { it.readBytes() } ?: throw IOException("Couldn't read $name")
        val file = newFile(name)
        if (options.removeDetails) {
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            file.writeBytes(stripJpegMetadata(bytes))
            // Stripping took the orientation with it; put that one tag back so portrait shots stay upright.
            if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()) }.saveAttributes()
            }
        } else {
            file.writeBytes(bytes)
            ExifInterface(file).apply {
                GPS_TAGS.forEach { setAttribute(it, null) }
                setAttribute(ExifInterface.TAG_XMP, null)   // XMP can carry a location too
            }.saveAttributes()
        }
        return uriFor(file)
    }

    private fun copyExif(from: ExifInterface, file: File, keepLocation: Boolean) {
        runCatching {
            val to = ExifInterface(file)
            (DETAIL_TAGS + if (keepLocation) GPS_TAGS else emptyList()).forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())   // pixels are upright now
            to.saveAttributes()
        }
    }

    private fun captionOf(exif: ExifInterface, withModel: Boolean): String = cardCaption(
        model = if (withModel) exif.getAttribute(ExifInterface.TAG_MODEL) else null,
        focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0),
        fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0),
        exposureSec = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0),
        iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1),
    )

    // endregion

    private fun newFile(name: String): File {
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, name.replace('/', '_'))
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > width) end--
        return text.substring(0, end) + "…"
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MIME_JPEG = "image/jpeg"
        const val MIME_HEIC = "image/vnd.android.heic"
        const val HEIF_TIMEOUT_MS = 30_000L
        const val SHEET_PX = 900

        // A4 in PDF points, and the contact sheet's grid.
        const val A4_W = 595f
        const val A4_H = 842f
        const val MARGIN = 36f
        const val GAP = 12f
        const val COLUMNS = 3

        // The printed card and contact sheet are paper, not the app's dark theme.
        val PAPER = Color.rgb(0xF6, 0xF6, 0xF2)
        val WELL = Color.rgb(0xEE, 0xEF, 0xEB)
        val INK = Color.rgb(0x1F, 0x24, 0x21)
        val INK_SOFT = Color.rgb(0x5C, 0x64, 0x5F)

        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )

        /** What a re-encoded copy keeps from the original: the camera, lens, settings and time. */
        val DETAIL_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        )
    }
}

/** Remembers which app the share sheet sent to, for the "Last used" chip. */
class ShareChosenReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        component?.let { Settings(context).lastShareApp = it.flattenToString() }
    }
}

private fun uniqueName(name: String, used: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (!used.add(candidate)) candidate = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}".removeSuffix(".")
        .also { n++ }
    return candidate
}

private fun commonMime(mimes: List<String>): String = when {
    mimes.distinct().size == 1 -> mimes.first()
    mimes.all { it.startsWith("image/") } -> "image/*"
    mimes.all { it.startsWith("video/") } -> "video/*"
    else -> "*/*"
}
