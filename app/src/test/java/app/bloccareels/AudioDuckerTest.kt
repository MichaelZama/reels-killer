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

// La proprietà non si chiama "muted": il suo setter JVM (setMuted) andrebbe in conflitto con MusicStream.setMuted.
private class FakeStream(var mutedNow: Boolean = false) : MusicStream {
    override val isMuted: Boolean get() = mutedNow
    override fun setMuted(muted: Boolean) { mutedNow = muted }
}

class AudioDuckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun marker() = tmp.root.resolve("audio_silenziato")

    @Test
    fun `mute lascia il segno su disco e restore lo toglie`() {
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
    fun `audio gia muto dall'utente non viene toccato e non lascia segni`() {
        val stream = FakeStream(mutedNow = true)
        val d = AudioDucker(stream, marker())

        d.mute()
        assertFalse(marker().exists())
        d.restore()
        assertTrue("il muto dell'utente resta", stream.mutedNow)
    }

    @Test
    fun `dopo la morte del processo il nuovo avvio riattiva l'audio`() {
        val stream = FakeStream()
        AudioDucker(stream, marker()).mute()
        // Il processo muore qui: restore() non viene mai chiamato.

        val result = AudioDucker(stream, marker()).recoverAfterProcessDeath()
        assertNotNull(result)
        assertFalse(stream.mutedNow)
        assertFalse(marker().exists())
    }

    @Test
    fun `segno rimasto ma audio gia attivo, il segno si cancella e basta`() {
        val stream = FakeStream()
        marker().writeText("")

        assertNotNull(AudioDucker(stream, marker()).recoverAfterProcessDeath())
        assertFalse(stream.mutedNow)
        assertFalse(marker().exists())
    }

    @Test
    fun `senza segno il ripristino non tocca il muto dell'utente`() {
        val stream = FakeStream(mutedNow = true)

        assertNull(AudioDucker(stream, marker()).recoverAfterProcessDeath())
        assertTrue(stream.mutedNow)
    }
}
