package app.bloccareels.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies the image, GIF or video picked by the user into the app's private folder. */
object MediaImporter {

    sealed interface Result {
        data class Ok(val media: CustomMedia) : Result
        data object TooLong : Result
        data object TooBig : Result
        data object Unsupported : Result
        data class Failed(val error: Throwable) : Result
    }

    const val MAX_VIDEO_SECONDS = 60
    private const val MAX_BYTES = 100L * 1024 * 1024

    private fun mediaDir(context: Context) = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver
            val mime = cr.getType(uri).orEmpty()
            val kind = when {
                mime == "image/gif" -> MediaKind.GIF
                mime.startsWith("image/") -> MediaKind.IMAGE
                mime.startsWith("video/") -> MediaKind.VIDEO
                else -> return@withContext Result.Unsupported
            }
            val ext = when (kind) {
                MediaKind.GIF -> "gif"
                MediaKind.IMAGE -> "img"
                MediaKind.VIDEO -> "mp4"
            }
            val target = File(mediaDir(context), "media-${System.currentTimeMillis()}.$ext")
            val copied = cr.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@use false
                        out.write(buf, 0, n)
                    }
                    true
                }
            } ?: false
            if (!copied) {
                target.delete()
                return@withContext Result.TooBig
            }
            if (kind == MediaKind.VIDEO) {
                val seconds = videoDurationMs(target.path)?.div(1000)
                if (seconds == null) {
                    target.delete()
                    return@withContext Result.Unsupported
                }
                if (seconds > MAX_VIDEO_SECONDS) {
                    target.delete()
                    return@withContext Result.TooLong
                }
            }
            Result.Ok(CustomMedia(target.path, kind))
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    /** Deletes the files that neither a level nor the general media use any more. */
    fun cleanup(context: Context, keep: Set<String>) {
        mediaDir(context).listFiles()?.filter { it.path !in keep }?.forEach { it.delete() }
    }

    private fun videoDurationMs(path: String): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** Small thumbnail for the settings. */
    suspend fun thumbnail(media: CustomMedia, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!File(media.path).exists()) return@withContext null
            when (media.kind) {
                MediaKind.VIDEO -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(media.path)
                        r.getFrameAtTime(0)?.let { scale(it, sizePx) }
                    } finally {
                        try { r.release() } catch (_: Exception) { }
                    }
                }
                else -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(media.path, bounds)
                    var sample = 1
                    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
                    BitmapFactory.decodeFile(media.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scale(b: Bitmap, size: Int): Bitmap {
        val f = size.toFloat() / minOf(b.width, b.height)
        if (f >= 1f) return b
        val w = (b.width * f).toInt().coerceAtLeast(1)
        val h = (b.height * f).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}
