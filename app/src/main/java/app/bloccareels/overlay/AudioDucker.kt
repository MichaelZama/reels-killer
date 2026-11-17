package app.bloccareels.overlay

import android.content.Context
import android.media.AudioManager
import java.io.File

/** The phone's media stream. It is an interface so that [AudioDucker] can be tested without Android. */
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
 * Mutes and restores the media stream.
 * Every method returns a line for the diagnostics log.
 *
 * Mute is a system setting: if the process dies while the audio is muted (force stop,
 * the system reclaiming memory) it can stay muted. That is why, as long as we keep the
 * audio muted, the [marker] file exists on disk: when the process starts,
 * [recoverAfterProcessDeath] finds it and unmutes the audio.
 */
class AudioDucker(private val stream: MusicStream, private val marker: File) {
    private var mutedByUs = false

    fun mute(): String {
        if (mutedByUs) return "Audio: already muted by Reels Killer"
        if (stream.isMuted) return "Audio: already muted, left alone"
        // Marker on disk first, then mute: if the process dies in between, the audio comes back on restart.
        val marked = try {
            marker.writeText("")
            true
        } catch (_: Exception) {
            false
        }
        stream.setMuted(true)
        mutedByUs = true
        val note = if (marked) "" else ", marker not written"
        return "Audio muted (muted now: ${stream.isMuted}$note)"
    }

    fun restore(): String {
        if (!mutedByUs) return "Audio: nothing to restore"
        mutedByUs = false
        stream.setMuted(false)
        marker.delete()
        return "Audio restored (muted now: ${stream.isMuted})"
    }

    /**
     * Call when the process starts, before any [mute]. If the marker is still there, the previous
     * session ended while we had muted the audio, and it must be unmuted.
     * Returns null if there was nothing to do.
     */
    fun recoverAfterProcessDeath(): String? {
        if (!marker.exists()) return null
        marker.delete()
        if (!stream.isMuted) return "Audio: previous session interrupted, but the audio was already on"
        stream.setMuted(false)
        return "Audio unmuted: it was left muted by an interrupted session (muted now: ${stream.isMuted})"
    }

    companion object {
        /** The file name is kept from older versions, so a marker they left behind is still found. */
        fun markerFile(context: Context): File = File(context.noBackupFilesDir, "audio_silenziato")
    }
}
