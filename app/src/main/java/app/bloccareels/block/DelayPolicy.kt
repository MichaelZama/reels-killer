package app.bloccareels.block

/** How long to wait before "Continue" can be tapped. */
object DelayPolicy {
    /** Wait in seconds, indexed by the number of consecutive "Continue" taps; the last value repeats. */
    val ESCALATION_STEPS = listOf(0, 2, 5, 10, 15, 20, 30)

    /** After this long without a block screen the session is over and the count starts again. */
    const val SESSION_GAP_MS: Long = 10 * 60 * 1000L

    /** [base] if not [escalating]; otherwise the step for [consecutiveContinues], never below [base]. */
    fun waitSeconds(base: Int, escalating: Boolean, consecutiveContinues: Int): Int =
        if (!escalating) base
        else maxOf(base, ESCALATION_STEPS[minOf(consecutiveContinues.coerceAtLeast(0), ESCALATION_STEPS.lastIndex)])

    /**
     * Consecutive "Continue" taps to use for a new block: zero if at least [SESSION_GAP_MS]
     * have passed since the block screen was last visible.
     */
    fun consecutiveForNewBlock(consecutiveContinues: Int, lastVisibleAtMs: Long?, nowMs: Long): Int =
        if (lastVisibleAtMs != null && nowMs - lastVisibleAtMs >= SESSION_GAP_MS) 0
        else consecutiveContinues
}
