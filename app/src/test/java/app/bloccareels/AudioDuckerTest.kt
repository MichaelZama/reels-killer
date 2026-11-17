package app.bloccareels

import app.bloccareels.overlay.AudioDucker
import app.bloccareels.overlay.MusicStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The property is not called "muted": its JVM setter (setMuted) would clash with MusicStream.setMuted.
private class FakeStream(var mutedNow: Boolean = false) : MusicStream {
    override val isMuted: Boolean get() = mutedNow
    override fun setMuted(muted: Boolean) { mutedNow = muted }
}

class AudioDuckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun marker() = tmp.root.resolve("audio_silenziato")

    @Test
    fun `mute leaves the marker on disk and restore removes it`() {
        val stream = FakeStream()
        val d = AudioDucker(stream, marker())

        d.mute()
        assertTrue(stream.mutedNow)
        assertTrue(marker().exists())

        d.restore()
        assertFalse(stream.mutedNow)
        assertFalse(marker().exists())
    }

    @Test
    fun `audio already muted by the user is left alone and leaves no marker`() {
        val stream = FakeStream(mutedNow = true)
        val d = AudioDucker(stream, marker())

        d.mute()
        assertFalse(marker().exists())
        d.restore()
        assertTrue("the user's mute stays", stream.mutedNow)
    }

    @Test
    fun `after the process dies the next start unmutes the audio`() {
        val stream = FakeStream()
        AudioDucker(stream, marker()).mute()
        // The process dies here: restore() is never called.

        val result = AudioDucker(stream, marker()).recoverAfterProcessDeath()
        assertNotNull(result)
        assertFalse(stream.mutedNow)
        assertFalse(marker().exists())
    }

    @Test
    fun `marker left but audio already on, the marker is just deleted`() {
        val stream = FakeStream()
        marker().writeText("")

        assertNotNull(AudioDucker(stream, marker()).recoverAfterProcessDeath())
        assertFalse(stream.mutedNow)
        assertFalse(marker().exists())
    }

    @Test
    fun `without a marker the recovery does not touch the user's mute`() {
        val stream = FakeStream(mutedNow = true)

        assertNull(AudioDucker(stream, marker()).recoverAfterProcessDeath())
        assertTrue(stream.mutedNow)
    }
}
