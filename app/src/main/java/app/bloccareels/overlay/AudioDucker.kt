package app.bloccareels.overlay

import android.media.AudioManager

/**
 * Silenzia e ripristina il flusso multimediale (sezione 8.3).
 * Ogni metodo restituisce una riga per il log di diagnostica.
 */
class AudioDucker(private val am: AudioManager) {
    private var mutedByUs = false

    fun mute(): String {
        if (mutedByUs) return "Audio: già silenziato da Reels Killer"
        if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return "Audio: era già muto, non lo tocco"
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedByUs = true
        return "Audio silenziato (muto ora: ${am.isStreamMute(AudioManager.STREAM_MUSIC)})"
    }

    fun restore(): String {
        if (!mutedByUs) return "Audio: niente da ripristinare"
        mutedByUs = false
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        return "Audio ripristinato (muto ora: ${am.isStreamMute(AudioManager.STREAM_MUSIC)})"
    }
}
