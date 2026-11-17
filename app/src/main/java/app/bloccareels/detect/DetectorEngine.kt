package app.bloccareels.detect

/**
 * Motore delle regole. Kotlin puro: lavora solo su [UiSnapshot].
 */
class DetectorEngine(val rules: RulesFile) {

    private val byPackage: Map<String, AppRules> =
        rules.apps.flatMap { app -> app.packages.map { it to app } }.toMap()

    val allPackages: Set<String> get() = byPackage.keys

    fun appForPackage(pkg: String?): AppRules? = pkg?.let { byPackage[it] }

    fun appById(id: String): AppRules? = rules.apps.firstOrNull { it.id == id }

    /** True se il package appartiene a un'app monitorata e abilitata. */
    fun isMonitored(pkg: String?, enabledAppIds: Set<String>): Boolean =
        appForPackage(pkg)?.let { it.id in enabledAppIds } == true

    fun detect(snapshot: UiSnapshot, enabledAppIds: Set<String>): Detection {
        val app = appForPackage(snapshot.packageName) ?: return Detection.None
        if (app.id !in enabledAppIds) return Detection.None
        val ctx = EvalContext(snapshot)
        for (rule in app.rules) {
            if (!ruleMatches(rule, ctx)) continue
            return when (rule.mode) {
                Rule.MODE_SINGLE -> Detection.Single(
                    appId = app.id,
                    rule = rule.name,
                    pagerViewId = rule.pagerViewId,
                    fingerprint = rule.pagerViewId?.let { snapshot.fingerprint(it) },
                )
                else -> Detection.Feed(app.id, rule.name)
            }
        }
        return Detection.None
    }

    /**
     * Prima le condizioni con viewId, che sono una ricerca mirata; poi quelle senza, che richiedono
     * di visitare l'albero. Se fallisce una condizione veloce la visita non si fa: per esempio nella
     * home di YouTube manca il player degli Shorts e la scheda selezionata non viene cercata.
     */
    private fun ruleMatches(rule: Rule, ctx: EvalContext): Boolean {
        if (rule.always) return true
        if (rule.allOf.isEmpty() && rule.anyOf.isEmpty()) return false
        val (fastAllOf, slowAllOf) = rule.allOf.partition { it.viewId != null }
        val anyOfIsFast = rule.anyOf.all { it.viewId != null }
        if (!fastAllOf.all { conditionMatches(it, ctx) }) return false
        if (anyOfIsFast && !anyOfMatches(rule, ctx)) return false
        if (!slowAllOf.all { conditionMatches(it, ctx) }) return false
        if (!anyOfIsFast && !anyOfMatches(rule, ctx)) return false
        return true
    }

    private fun anyOfMatches(rule: Rule, ctx: EvalContext): Boolean =
        rule.anyOf.isEmpty() || rule.anyOf.any { conditionMatches(it, ctx) }

    private fun conditionMatches(c: Condition, ctx: EvalContext): Boolean {
        val candidates: List<UiNode> =
            if (c.viewId != null) ctx.byViewId(c.viewId) else ctx.allNodes()
        return candidates.any { nodeMatches(it, c, ctx.snapshot.screenHeightPx) }
    }

    private fun nodeMatches(n: UiNode, c: Condition, screenHeight: Int): Boolean {
        if (!n.isVisible) return false
        if (c.viewId != null && n.viewId != c.viewId) return false
        if (c.selected != null && n.isSelected != c.selected) return false
        if (c.scrollable != null && n.isScrollable != c.scrollable) return false
        if (c.minHeightFraction != null) {
            if (screenHeight <= 0) return false
            if (n.heightPx < c.minHeightFraction * screenHeight) return false
        }
        if (c.descAnyOf != null) {
            val d = n.contentDescription ?: return false
            if (c.descAnyOf.none { it.equals(d, ignoreCase = true) }) return false
        }
        if (c.textAnyOf != null) {
            val t = n.text ?: return false
            if (c.textAnyOf.none { it.equals(t, ignoreCase = true) }) return false
        }
        return true
    }

    /** Cache per una singola valutazione: ogni ricerca e la visita completa si fanno una volta sola. */
    private class EvalContext(val snapshot: UiSnapshot) {
        private val idCache = HashMap<String, List<UiNode>>()
        private var all: List<UiNode>? = null
        fun byViewId(id: String): List<UiNode> = idCache.getOrPut(id) { snapshot.findByViewId(id) }
        fun allNodes(): List<UiNode> = all ?: snapshot.walk(MAX_WALK_NODES).toList().also { all = it }
    }

    companion object {
        const val MAX_WALK_NODES = 400
    }
}
