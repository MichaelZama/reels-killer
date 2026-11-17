package app.bloccareels.block

/** Calcolo dell'attesa prima che "Continua" diventi cliccabile. */
object DelayPolicy {
    /** Secondi di attesa per numero di "Continua" consecutivi. */
    val SCALA = listOf(0, 2, 5, 10, 15, 20, 30)

    /** Dopo questo intervallo senza schermata di blocco la sessione finisce. */
    const val SESSION_GAP_MS: Long = 10 * 60 * 1000L

    fun attesa(base: Int, crescente: Boolean, consecutivi: Int): Int =
        if (!crescente) base
        else maxOf(base, SCALA[minOf(consecutivi.coerceAtLeast(0), SCALA.lastIndex)])

    /**
     * Restituisce i "Continua" consecutivi da usare per un nuovo blocco:
     * zero se dall'ultima volta in cui la schermata era visibile sono passati almeno 10 minuti.
     */
    fun consecutiviAggiornati(consecutivi: Int, ultimaVisibilitaMs: Long?, adessoMs: Long): Int =
        if (ultimaVisibilitaMs != null && adessoMs - ultimaVisibilitaMs >= SESSION_GAP_MS) 0
        else consecutivi
}
