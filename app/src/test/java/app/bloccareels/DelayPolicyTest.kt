package app.bloccareels

import app.bloccareels.block.DelayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayPolicyTest {

    @Test
    fun `base 0 with escalating wait follows the steps`() {
        val waits = (0..7).map { DelayPolicy.waitSeconds(base = 0, escalating = true, consecutiveContinues = it) }
        assertEquals(listOf(0, 2, 5, 10, 15, 20, 30, 30), waits)
    }

    @Test
    fun `base 5 with escalating wait never goes below the base`() {
        val waits = (0..6).map { DelayPolicy.waitSeconds(base = 5, escalating = true, consecutiveContinues = it) }
        assertEquals(listOf(5, 5, 5, 10, 15, 20, 30), waits)
    }

    @Test
    fun `with escalating wait off the base is always returned`() {
        for (base in listOf(0, 3, 5, 10)) {
            for (n in 0..10) assertEquals(base, DelayPolicy.waitSeconds(base, escalating = false, consecutiveContinues = n))
        }
    }

    @Test
    fun `consecutive Continues reset after 10 minutes without blocks`() {
        val tenMinutes = DelayPolicy.SESSION_GAP_MS
        assertEquals(4, DelayPolicy.consecutiveForNewBlock(4, lastVisibleAtMs = 1_000, nowMs = 1_000 + tenMinutes - 1))
        assertEquals(0, DelayPolicy.consecutiveForNewBlock(4, lastVisibleAtMs = 1_000, nowMs = 1_000 + tenMinutes))
        assertEquals(3, DelayPolicy.consecutiveForNewBlock(3, lastVisibleAtMs = null, nowMs = 99_999_999))
    }
}
