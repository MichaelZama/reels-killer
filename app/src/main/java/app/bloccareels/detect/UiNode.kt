package app.bloccareels.detect

/** Vista astratta della schermata in primo piano. Nessuna dipendenza da Android. */
interface UiSnapshot {
    val packageName: String
    val screenHeightPx: Int
    fun findByViewId(id: String): List<UiNode>
    fun walk(maxNodes: Int): Sequence<UiNode>

    /**
     * Impronta del video corrente: hash dei primi tre testi non vuoti
     * dei discendenti del contenitore [viewId]. Null se non calcolabile.
     * Non viene mai salvata né registrata nel log.
     */
    fun fingerprint(viewId: String): Int? = null
}

interface UiNode {
    /** Parte dopo ":id/". */
    val viewId: String?
    val text: String?
    val contentDescription: String?
    val isSelected: Boolean
    val isScrollable: Boolean
    val isVisible: Boolean
    val heightPx: Int
}

sealed interface Detection {
    data object None : Detection
    data class Feed(val appId: String, val rule: String) : Detection
    data class Single(
        val appId: String,
        val rule: String,
        val pagerViewId: String?,
        /** Impronta del video: non va mai nel log, vedi [describe]. */
        val fingerprint: Int? = null,
    ) : Detection
}

/** Descrizione leggibile per la diagnostica, senza l'impronta. */
fun Detection.describe(): String = when (this) {
    Detection.None -> "Nessuno"
    is Detection.Feed -> "Sezione · $appId · $rule"
    is Detection.Single -> "Singolo · $appId · $rule"
}
