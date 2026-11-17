package app.bloccareels.overlay

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import app.bloccareels.R
import app.bloccareels.block.OverlayModel
import app.bloccareels.data.Illustration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Riempie la vista della schermata di blocco e gestisce il conto alla rovescia.
 * Usato sia dall'overlay del servizio sia dall'anteprima nelle impostazioni.
 */
class OverlayBinder(
    private val root: View,
    private val scope: CoroutineScope,
    onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val res = root.resources
    private val illustration: ImageView = root.findViewById(R.id.overlay_illustration)
    private val title: TextView = root.findViewById(R.id.overlay_title)
    private val subtitle: TextView = root.findViewById(R.id.overlay_subtitle)
    private val exit: View = root.findViewById(R.id.overlay_exit)
    private val continueButton: View = root.findViewById(R.id.overlay_continue)
    private val ring: RingView = root.findViewById(R.id.overlay_ring)
    private val continueText: TextView = root.findViewById(R.id.overlay_continue_text)
    private val counter: TextView = root.findViewById(R.id.overlay_counter)

    private var countdown: Job? = null

    private val media = CustomMediaView(
        container = root.findViewById(R.id.overlay_media),
        image = root.findViewById(R.id.overlay_media_image),
        video = root.findViewById(R.id.overlay_media_video),
        scope = scope,
        onError = onError,
    )

    fun bind(model: OverlayModel, onExit: () -> Unit, onContinue: () -> Unit) {
        countdown?.cancel()
        val custom = model.customMedia
        if (custom != null) {
            illustration.visibility = View.GONE
            media.show(custom)
        } else {
            media.release()
            illustration.visibility = View.VISIBLE
            illustration.setImageResource(drawableFor(model.illustration))
        }
        title.text = model.message
        subtitle.text = model.subtitle
        subtitle.visibility = if (model.subtitle.isBlank()) View.GONE else View.VISIBLE
        counter.text = if (model.showEscalationHint) {
            res.getString(R.string.overlay_counter_escalating, model.blocksToday)
        } else {
            res.getString(R.string.overlay_counter, model.blocksToday)
        }
        exit.contentDescription = res.getString(R.string.overlay_exit_description)
        exit.setOnClickListener { onExit() }
        continueButton.setOnClickListener { if (continueButton.isEnabled) onContinue() }

        if (model.waitSeconds <= 0) {
            setContinueEnabled()
        } else {
            countdown = scope.launch {
                val total = model.waitSeconds
                for (left in total downTo 1) {
                    setContinueWaiting(left, total)
                    delay(1000)
                }
                setContinueEnabled()
            }
        }
    }

    fun cancel() {
        countdown?.cancel()
        countdown = null
        media.release()
    }

    private fun setContinueWaiting(left: Int, total: Int) {
        continueButton.isEnabled = false
        continueButton.alpha = 0.75f
        ring.visibility = View.VISIBLE
        ring.progress = (total - left).toFloat() / total
        continueText.text = res.getString(R.string.overlay_continue_in, left)
        continueButton.contentDescription = res.getString(R.string.overlay_continue_in_description, left)
    }

    private fun setContinueEnabled() {
        continueButton.isEnabled = true
        continueButton.alpha = 1f
        ring.visibility = View.GONE
        continueText.setText(R.string.overlay_continue)
        continueButton.contentDescription = res.getString(R.string.overlay_continue_description)
    }

    companion object {
        fun drawableFor(i: Illustration): Int = when (i) {
            Illustration.TRAMONTO -> R.drawable.ill_tramonto
            Illustration.TAZZA -> R.drawable.ill_tazza
            Illustration.FOGLIA -> R.drawable.ill_foglia
            // Non viene disegnato: con "personalizzato" la schermata mostra il media dell'utente.
            // Serve solo a rendere completo il when.
            Illustration.PERSONALIZZATO -> R.drawable.ill_tramonto
        }
    }
}
