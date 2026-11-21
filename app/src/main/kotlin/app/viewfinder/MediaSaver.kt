package app.viewfinder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/** Writes downloads into the phone's shared media, where Photos, Lightroom and Snapseed can see them. */
object MediaSaver {
    const val FOLDER = "Viewfinder"

    fun mimeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "nef" -> "image/x-nikon-nef"
        "nrw" -> "image/x-nikon-nrw"
        "cr2", "cr3" -> "image/x-canon-${filename.substringAfterLast('.').lowercase()}"
        "arw" -> "image/x-sony-arw"
        "raf" -> "image/x-fuji-raf"
        "dng" -> "image/x-adobe-dng"
        "heif", "hif", "heic" -> "image/heif"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** Where a saved file shows up, for the confirmation message. */
    fun folderFor(mime: String): String = when {
        mime.startsWith("video/") -> "Movies/$FOLDER"
        mime.startsWith("image/") -> "Pictures/$FOLDER"
        else -> "Download/$FOLDER"
    }

    suspend fun save(context: Context, filename: String, write: suspend (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val mime = mimeFor(filename)
        val collection = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(mime))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Android refused to create $filename")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open $filename for writing")
            out.buffered(256 * 1024).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
