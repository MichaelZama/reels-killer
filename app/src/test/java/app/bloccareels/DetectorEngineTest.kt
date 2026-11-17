package app.bloccareels

import app.bloccareels.detect.Detection
import app.bloccareels.detect.DetectorEngine
import app.bloccareels.detect.ExitAction
import app.bloccareels.detect.RulesFile
import app.bloccareels.detect.RulesLoader
import app.bloccareels.detect.UiNode
import app.bloccareels.detect.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

data class FakeNode(
    override val viewId: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val isSelected: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isVisible: Boolean = true,
    override val heightPx: Int = 100,
) : UiNode

class FakeSnapshot(
    override val packageName: String,
    private val nodes: List<FakeNode>,
    override val screenHeightPx: Int = 2400,
    private val fp: Int? = null,
) : UiSnapshot {
    /** Quante volte è stato visitato l'albero intero. */
    var walks = 0
        private set

    override fun findByViewId(id: String): List<UiNode> = nodes.filter { it.viewId == id }
    override fun walk(maxNodes: Int): Sequence<UiNode> {
        walks++
        return nodes.asSequence().take(maxNodes)
    }
    override fun fingerprint(viewId: String): Int? = fp
}

class DetectorEngineTest {

    private val rules: RulesFile = RulesLoader.parse(loadBundledRules()) { throw AssertionError(it) }
    private val engine = DetectorEngine(rules)
    private val all = setOf("instagram", "youtube", "tiktok", "facebook")

    private fun loadBundledRules(): String {
        val candidates = listOf("src/main/assets/rules.json", "app/src/main/assets/rules.json")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    @Test
    fun `il file delle regole incluso si legge`() {
        assertEquals(4, rules.apps.size)
        assertTrue(rules.version >= 1)
        assertEquals(ExitAction.HOME, rules.apps.first { it.id == "tiktok" }.exitAction)
    }

    @Test
    fun `package non monitorato restituisce None`() {
        val snap = FakeSnapshot("com.whatsapp", listOf(FakeNode(viewId = "clips_viewer_view_pager")))
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `regola always restituisce Feed`() {
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.Feed("tiktok", "tutta_la_app"), engine.detect(snap, all))
        val trill = FakeSnapshot("com.ss.android.ugc.trill", emptyList())
        assertEquals(Detection.Feed("tiktok", "tutta_la_app"), engine.detect(trill, all))
    }

    @Test
    fun `contenitore presente e scheda selezionata restituisce Feed`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager"), FakeNode(viewId = "clips_tab", isSelected = true)),
        )
        assertEquals(Detection.Feed("instagram", "scheda_reels"), engine.detect(snap, all))
    }

    @Test
    fun `contenitore presente e scheda non selezionata restituisce Single con pagerViewId`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager"), FakeNode(viewId = "clips_tab", isSelected = false)),
            fp = 42,
        )
        assertEquals(
            Detection.Single("instagram", "reel_singolo", "clips_viewer_view_pager", fingerprint = 42),
            engine.detect(snap, all),
        )
    }

    @Test
    fun `contenitore presente ma non visibile restituisce None`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager", isVisible = false), FakeNode(viewId = "clips_tab", isSelected = true)),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `vince la prima regola che corrisponde`() {
        val json = """
            {"version":1,"apps":[{"id":"instagram","packages":["p"],"rules":[
              {"name":"prima","mode":"single","allOf":[{"viewId":"a"}]},
              {"name":"seconda","mode":"feed","allOf":[{"viewId":"a"}]}
            ]}]}
        """.trimIndent()
        val e = DetectorEngine(RulesLoader.parse(json))
        val d = e.detect(FakeSnapshot("p", listOf(FakeNode(viewId = "a"))), setOf("instagram"))
        assertEquals("prima", (d as Detection.Single).rule)
    }

    @Test
    fun `descAnyOf ignora maiuscole e minuscole`() {
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(viewId = "reel_recycler"), FakeNode(contentDescription = "SHORTS", isSelected = true)),
        )
        assertEquals(Detection.Feed("youtube", "scheda_shorts"), engine.detect(snap, all))
    }

    @Test
    fun `allOf e anyOf devono essere entrambi soddisfatti`() {
        // Scheda Shorts selezionata ma nessun player: niente.
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(contentDescription = "Shorts", isSelected = true)),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `minHeightFraction distingue il player a tutto schermo`() {
        val tab = FakeNode(contentDescription = "Reels", isSelected = true)
        val piccolo = FakeSnapshot("com.facebook.katana", listOf(tab, FakeNode(isScrollable = true, heightPx = 600)))
        val grande = FakeSnapshot("com.facebook.katana", listOf(tab, FakeNode(isScrollable = true, heightPx = 2300)))
        assertEquals(Detection.None, engine.detect(piccolo, all))
        assertEquals(Detection.Feed("facebook", "scheda_reels"), engine.detect(grande, all))
    }

    @Test
    fun `app disattivata nelle impostazioni restituisce None`() {
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.None, engine.detect(snap, all - "tiktok"))
        assertEquals(Detection.None, engine.detect(snap, emptySet()))
    }

    @Test
    fun `file delle regole malformato non carica regole e registra un errore`() {
        val errors = mutableListOf<String>()
        val parsed = RulesLoader.parse("{ questo non è json") { errors += it }
        assertEquals(RulesFile.EMPTY, parsed)
        assertEquals(1, errors.size)
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.None, DetectorEngine(parsed).detect(snap, all))
    }

    @Test
    fun `mode o exitAction sconosciuti rendono non valido il file`() {
        val badMode = """{"version":1,"apps":[{"id":"x","packages":["p"],"rules":[{"name":"r","mode":"feeed","always":true}]}]}"""
        val badExit = """{"version":1,"apps":[{"id":"x","packages":["p"],"exitAction":"sideways","rules":[]}]}"""
        for (json in listOf(badMode, badExit)) {
            val errors = mutableListOf<String>()
            assertEquals(RulesFile.EMPTY, RulesLoader.parse(json) { errors += it })
            assertEquals(1, errors.size)
        }
    }

    // ------------------------------------- ordine di valutazione: prima le condizioni con viewId

    @Test
    fun `senza il player degli Shorts l'albero non viene visitato`() {
        // Home di YouTube: la scheda Shorts non è selezionata e il player non c'è.
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(contentDescription = "Home", isSelected = true), FakeNode(contentDescription = "Shorts")),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
        assertEquals(0, snap.walks)
    }

    @Test
    fun `con il player degli Shorts l'albero si visita una volta sola`() {
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(viewId = "reel_recycler"), FakeNode(contentDescription = "Shorts", isSelected = true)),
        )
        assertEquals(Detection.Feed("youtube", "scheda_shorts"), engine.detect(snap, all))
        assertEquals(1, snap.walks)
    }

    @Test
    fun `una condizione allOf con viewId che fallisce evita la visita anche se anyOf e lenta`() {
        val json = """
            {"version":1,"apps":[{"id":"instagram","packages":["p"],"rules":[
              {"name":"r","mode":"feed","allOf":[{"viewId":"a"}],"anyOf":[{"descAnyOf":["X"]}]}
            ]}]}
        """.trimIndent()
        val e = DetectorEngine(RulesLoader.parse(json))

        val senzaA = FakeSnapshot("p", listOf(FakeNode(contentDescription = "X")))
        assertEquals(Detection.None, e.detect(senzaA, setOf("instagram")))
        assertEquals(0, senzaA.walks)

        val conA = FakeSnapshot("p", listOf(FakeNode(viewId = "a"), FakeNode(contentDescription = "X")))
        assertEquals(Detection.Feed("instagram", "r"), e.detect(conA, setOf("instagram")))

        val conASenzaX = FakeSnapshot("p", listOf(FakeNode(viewId = "a"), FakeNode(contentDescription = "Y")))
        assertEquals(Detection.None, e.detect(conASenzaX, setOf("instagram")))
    }
}
