package app.bloccareels.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.bloccareels.R
import app.bloccareels.block.OverlayModel
import app.bloccareels.diag.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope

/**
 * Finestra della schermata di blocco (sezione 8). Tiene un solo riferimento alla vista.
 * Va usato solo dal thread principale.
 */
class OverlayManager(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
) {
    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)

    var onExit: () -> Unit = {}
    var onContinue: () -> Unit = {}

    private var view: View? = null
    private var binder: OverlayBinder? = null
    private var params: WindowManager.LayoutParams? = null

    /** Viste in dissolvenza di uscita, da rimuovere comunque in [removeAllNow]. */
    private val leaving = mutableSetOf<View>()

    val isShowing: Boolean get() = view != null

    fun show(model: OverlayModel) {
        val existing = view
        if (existing != null) {
            binder?.bind(model, { onExit() }, { onContinue() })
            return
        }
        val ctx = ContextThemeWrapper(service, R.style.Theme_BloccaReels)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_block, null)
        val content = v.findViewById<View>(R.id.overlay_content)
        ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            content.setPadding(bars.left, bars.top + dp(24), bars.right, bars.bottom + dp(24))
            insets
        }
        val p = layoutParams()
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            log.error("addView fallito", e)
            return
        }
        view = v
        params = p
        binder = OverlayBinder(v, scope) { what, e -> log.error(what, e) }.also { it.bind(model, { onExit() }, { onContinue() }) }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    fun hide() {
        val v = view ?: return
        binder?.cancel()
        binder = null
        view = null
        val p = params
        params = null
        // Durante la dissolvenza i tocchi passano già all'app sotto.
        if (p != null) {
            try {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(v, p)
            } catch (e: Exception) {
                log.error("updateViewLayout fallito", e)
            }
        }
        leaving += v
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { remove(v) }.start()
    }

    /** Rimozione immediata, senza dissolvenza: per quando il servizio si ferma. */
    fun removeAllNow() {
        binder?.cancel()
        binder = null
        view?.let { leaving += it }
        view = null
        params = null
        leaving.toList().forEach { remove(it) }
    }

    private fun remove(v: View) {
        if (!leaving.remove(v)) return
        v.animate().cancel()
        try {
            if (v.isAttachedToWindow) wm.removeViewImmediate(v)
        } catch (e: Exception) {
            log.error("removeView fallito", e)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Serve alla TextureView del video personalizzato.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowAnimations = 0
        title = "Blocca-Reels"
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    companion object {
        const val FADE_MS = 200L
    }
}
