package app.bloccareels.block

import app.bloccareels.data.CustomMedia
import app.bloccareels.data.Illustration
import app.bloccareels.data.MediaKind
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction

enum class BlockState { INATTIVO, OSSERVAZIONE, BLOCCATO, TREGUA }

/** Dati di un'app ospite che servono al controller: sottotitolo e azione di "Esci". */
data class AppMeta(val label: String, val exitAction: ExitAction)

data class OverlayModel(
    val message: String,
    val subtitle: String,
    val illustration: Illustration,
    val blocksToday: Int,
    /** 0 = "Continua" subito attivo. */
    val waitSeconds: Int,
    val showEscalationHint: Boolean,
    /** Immagine, GIF o video scelti dall'utente; null = disegno [illustration]. */
    val customMedia: CustomMedia? = null,
) {
    /** Un video personalizzato ha il suo audio: in quel caso non si silenzia il flusso multimediale. */
    val playsOwnAudio: Boolean get() = customMedia?.kind == MediaKind.VIDEO
}

/** Comandi che il controller manda verso Android. */
interface BlockActions {
    fun showOverlay(model: OverlayModel)
    fun hideOverlay()
    fun muteAudio()
    fun restoreAudio()
    fun performExit(action: ExitAction)

    /** Valutazione sincrona delle regole. */
    fun evaluateNow(): Detection
}

/** Contatori giornalieri, salvati dal repository. */
interface Counters {
    /** Incrementa i blocchi di oggi e restituisce il nuovo totale. */
    fun onBlockShown(): Int
    fun onContinue()
    fun onExit()
}
