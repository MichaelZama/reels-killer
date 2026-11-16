package app.bloccareels.block

import app.bloccareels.data.CustomMedia
import app.bloccareels.data.Illustration
import app.bloccareels.data.MediaKind
import app.bloccareels.detect.Detection

enum class ExitAction {
    BACK, HOME;

    companion object {
        fun parse(value: String?): ExitAction = if (value.equals("home", ignoreCase = true)) HOME else BACK
    }
}

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

/** Comandi che il controller manda verso Android (sezione 7.4). */
interface BlockActions {
    fun showOverlay(model: OverlayModel)
    fun hideOverlay()
    fun muteAudio()
    fun restoreAudio()
    fun performExit(action: ExitAction)

    /** Valutazione sincrona delle regole. */
    fun evaluateNow(): Detection
}

/** Contatori giornalieri e rotazione dei messaggi, salvati dal repository. */
interface Counters {
    /** Incrementa i blocchi di oggi e restituisce il nuovo totale. */
    fun onBlockShown(): Int
    fun onContinue()
    fun onExit()
    fun onMessageShown(index: Int)
}
