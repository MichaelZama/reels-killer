package app.bloccareels

import app.bloccareels.block.DelayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayPolicyTest {

    @Test
    fun `base 0 con attesa crescente segue la scala`() {
        val attese = (0..7).map { DelayPolicy.attesa(base = 0, crescente = true, consecutivi = it) }
        assertEquals(listOf(0, 2, 5, 10, 15, 20, 30, 30), attese)
    }

    @Test
    fun `base 5 con attesa crescente non scende mai sotto la base`() {
        val attese = (0..6).map { DelayPolicy.attesa(base = 5, crescente = true, consecutivi = it) }
        assertEquals(listOf(5, 5, 5, 10, 15, 20, 30), attese)
    }

    @Test
    fun `attesa crescente spenta restituisce sempre la base`() {
        for (base in listOf(0, 3, 5, 10)) {
            for (n in 0..10) assertEquals(base, DelayPolicy.attesa(base, crescente = false, consecutivi = n))
        }
    }

    @Test
    fun `consecutivi si azzerano dopo 10 minuti senza blocchi`() {
        val dieciMinuti = DelayPolicy.SESSION_GAP_MS
        assertEquals(4, DelayPolicy.consecutiviAggiornati(4, ultimaVisibilitaMs = 1_000, adessoMs = 1_000 + dieciMinuti - 1))
        assertEquals(0, DelayPolicy.consecutiviAggiornati(4, ultimaVisibilitaMs = 1_000, adessoMs = 1_000 + dieciMinuti))
        assertEquals(3, DelayPolicy.consecutiviAggiornati(3, ultimaVisibilitaMs = null, adessoMs = 99_999_999))
    }
}
