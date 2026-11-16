package app.bloccareels.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import app.bloccareels.data.CustomMedia
import app.bloccareels.data.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mostra un'immagine, una GIF animata o un video in loop con audio dentro la schermata di blocco.
 * Il video chiede il focus audio temporaneo: la maggior parte delle app sotto si mette in pausa.
 */
class CustomMediaView(
    private val container: FrameLayout,
    private val image: ImageView,
    private val video: TextureView,
    private val scope: CoroutineScope,
    private val onError: (String, Throwable) -> Unit,
) {
    private val context: Context = container.context
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentPath: String? = null

    init {
        val radius = 24f * context.resources.displayMetrics.density
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        container.clipToOutline = true
    }

    fun show(media: CustomMedia) {
        if (media.path == currentPath && container.visibility == View.VISIBLE) return
        release()
        currentPath = media.path
        if (!File(media.path).exists()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        when (media.kind) {
            MediaKind.VIDEO -> showVideo(media.path)
            MediaKind.IMAGE, MediaKind.GIF -> showImage(media.path, animated = media.kind == MediaKind.GIF)
        }
    }

    /** Ferma video e animazioni, libera il player e restituisce il focus audio. */
    fun release() {
        loadJob?.cancel()
        loadJob = null
        currentPath = null
        if (Build.VERSION.SDK_INT >= 28) (image.drawable as? AnimatedImageDrawable)?.stop()
        image.setImageDrawable(null)
        image.visibility = View.GONE
        video.surfaceTextureListener = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        surface?.release()
        surface = null
        video.visibility = View.GONE
        abandonFocus()
        container.visibility = View.GONE
    }

    // ---------------------------------------------------------------- immagini

    private fun showImage(path: String, animated: Boolean) {
        image.visibility = View.VISIBLE
        loadJob = scope.launch {
            val drawable: Drawable? = try {
                withContext(Dispatchers.IO) { decode(path, animated) }
            } catch (e: Exception) {
                onError("lettura immagine", e)
                null
            }
            if (drawable == null) {
                container.visibility = View.GONE
                return@launch
            }
            image.setImageDrawable(drawable)
            if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
        }
    }

    private fun decode(path: String, animated: Boolean): Drawable? {
        val max = MAX_IMAGE_PX
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(File(path))
            return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxOf(w, h).toFloat() / max
                if (scale > 1f) decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
                if (!animated) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // Android 8: le GIF restano ferme sul primo fotogramma.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }

    // ------------------------------------------------------------------- video

    private fun showVideo(path: String) {
        video.visibility = View.VISIBLE
        if (video.isAvailable) {
            startPlayer(path, video.surfaceTexture!!)
        } else {
            video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    if (currentPath == path) startPlayer(path, st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    release()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    private fun startPlayer(path: String, st: SurfaceTexture) {
        try {
            val s = Surface(st)
            surface = s
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(path)
                setSurface(s)
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h -> fitVideo(w, h) }
                setOnPreparedListener { mp ->
                    fitVideo(mp.videoWidth, mp.videoHeight)
                    requestFocus(attrs)
                    mp.start()
                }
                setOnErrorListener { _, what, extra ->
                    onError("riproduzione video", IllegalStateException("errore $what/$extra"))
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            onError("avvio video", e)
            release()
        }
    }

    /** Adatta la TextureView alle proporzioni del video, senza superare l'altezza massima. */
    private fun fitVideo(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val density = context.resources.displayMetrics.density
        val maxW = container.width.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - (64 * density).toInt())
        val maxH = (MAX_VIDEO_HEIGHT_DP * density).toInt()
        var vw = maxW
        var vh = (maxW.toFloat() * h / w).toInt()
        if (vh > maxH) {
            vh = maxH
            vw = (maxH.toFloat() * w / h).toInt()
        }
        val lp = video.layoutParams as FrameLayout.LayoutParams
        lp.width = vw
        lp.height = vh
        video.layoutParams = lp
    }

    private fun requestFocus(attrs: AudioAttributes) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        try {
            audioManager.requestAudioFocus(req)
        } catch (e: Exception) {
            onError("focus audio", e)
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_IMAGE_PX = 1440
        private const val MAX_VIDEO_HEIGHT_DP = 360
    }
}
