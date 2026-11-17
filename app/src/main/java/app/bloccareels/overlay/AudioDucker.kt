package app.bloccareels.overlay

import android.content.Context
import android.media.AudioManager
import java.io.File

/** Il flusso multimediale del telefono. È un'interfaccia così [AudioDucker] si prova senza Android. */
interface MusicStream {
    val isMuted: Boolean
    fun setMuted(muted: Boolean)
}

class SystemMusicStream(private val am: AudioManager) : MusicStream {
    override val isMuted: Boolean get() = am.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMuted(muted: Boolean) {
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
    }
}

/**
 * Silenzia e ripristina il flusso multimediale.
 * Ogni metodo restituisce una riga per il log di diagnostica.
 *
 * Il muto è un'impostazione di sistema: se il processo muore mentre l'audio è silenziato
 * (chiusura forzata, sistema che libera memoria) può restare muto. Per questo, finché l'audio
 * è silenziato da noi, su disco c'è il file [marker]: all'avvio del processo
 * [recoverAfterProcessDeath] lo trova e riattiva l'audio.
 */
class AudioDucker(private val stream: MusicStream, private val marker: File) {
    private var mutedByUs = false

    fun mute(): String {
        if (mutedByUs) return "Audio: già silenziato da Reels Killer"
        if (stream.isMuted) return "Audio: era già muto, non lo tocco"
        // Prima il segno su disco, poi il muto: se il processo muore in mezzo, al riavvio l'audio torna.
        val marked = try {
            marker.writeText("")
            true
        } catch (_: Exception) {
            false
        }
        stream.setMuted(true)
        mutedByUs = true
        val note = if (marked) "" else ", segno su disco non scritto"
        return "Audio silenziato (muto ora: ${stream.isMuted}$note)"
    }

    fun restore(): String {
        if (!mutedByUs) return "Audio: niente da ripristinare"
        mutedByUs = false
        stream.setMuted(false)
        marker.delete()
        return "Audio ripristinato (muto ora: ${stream.isMuted})"
    }

    /**
     * Da chiamare all'avvio del processo, prima di qualunque [mute]. Se il segno è rimasto, la sessione
     * precedente si è interrotta con l'audio silenziato da noi e va riattivato.
     * Restituisce null se non c'era niente da fare.
     */
    fun recoverAfterProcessDeath(): String? {
        if (!marker.exists()) return null
        marker.delete()
        if (!stream.isMuted) return "Audio: sessione precedente interrotta, ma l'audio era già attivo"
        stream.setMuted(false)
        return "Audio riattivato: era rimasto silenziato da una sessione interrotta (muto ora: ${stream.isMuted})"
    }

    companion object {
        fun markerFile(context: Context): File = File(context.noBackupFilesDir, "audio_silenziato")
    }
}
