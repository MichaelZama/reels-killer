package app.bloccareels.detect

/** Abstract view of the foreground screen. No Android dependency. */
interface UiSnapshot {
    val packageName: String
    val screenHeightPx: Int
    fun findByViewId(id: String): List<UiNode>
    fun walk(maxNodes: Int): Sequence<UiNode>

    /**
     * Fingerprint of the current video: hash of the first three non-empty texts
     * among the descendants of the [viewId] container. Null if it cannot be computed.
     * It is never saved or logged.
     */
    fun fingerprint(viewId: String): Int? = null
}

interface UiNode {
    /** The part after ":id/". */
    val viewId: String?
    val text: String?
    val contentDescription: String?
    val isSelected: Boolean
    val isScrollable: Boolean
    val isVisible: Boolean
    val heightPx: Int
}

/** Result of evaluating the rules on the foreground screen. */
sealed interface Detection {
    data object None : Detection
    data class Feed(val appId: String, val rule: String) : Detection
    data class Single(
        val appId: String,
        val rule: String,
        val pagerViewId: String?,
        /** Video fingerprint: it never goes into the log, see [describe]. */
        val fingerprint: Int? = null,
    ) : Detection
}

/** Readable description for diagnostics, without the fingerprint. */
fun Detection.describe(): String = when (this) {
    Detection.None -> "None"
    is Detection.Feed -> "Feed · $appId · $rule"
    is Detection.Single -> "Single · $appId · $rule"
}
