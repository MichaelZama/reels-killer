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
    /** How many times the whole tree was walked. */
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
    fun `the bundled rules file loads`() {
        assertEquals(4, rules.apps.size)
        assertTrue(rules.version >= 1)
        assertEquals(ExitAction.HOME, rules.apps.first { it.id == "tiktok" }.exitAction)
    }

    @Test
    fun `an unmonitored package returns None`() {
        val snap = FakeSnapshot("com.whatsapp", listOf(FakeNode(viewId = "clips_viewer_view_pager")))
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `an always rule returns Feed`() {
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.Feed("tiktok", "whole_app"), engine.detect(snap, all))
        val trill = FakeSnapshot("com.ss.android.ugc.trill", emptyList())
        assertEquals(Detection.Feed("tiktok", "whole_app"), engine.detect(trill, all))
    }

    @Test
    fun `container present and tab selected returns Feed`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager"), FakeNode(viewId = "clips_tab", isSelected = true)),
        )
        assertEquals(Detection.Feed("instagram", "reels_tab"), engine.detect(snap, all))
    }

    @Test
    fun `container present and tab not selected returns Single with pagerViewId`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager"), FakeNode(viewId = "clips_tab", isSelected = false)),
            fp = 42,
        )
        assertEquals(
            Detection.Single("instagram", "single_reel", "clips_viewer_view_pager", fingerprint = 42),
            engine.detect(snap, all),
        )
    }

    @Test
    fun `container present but not visible returns None`() {
        val snap = FakeSnapshot(
            "com.instagram.android",
            listOf(FakeNode(viewId = "clips_viewer_view_pager", isVisible = false), FakeNode(viewId = "clips_tab", isSelected = true)),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `the first matching rule wins`() {
        val json = """
            {"version":1,"apps":[{"id":"instagram","packages":["p"],"rules":[
              {"name":"first","mode":"single","allOf":[{"viewId":"a"}]},
              {"name":"second","mode":"feed","allOf":[{"viewId":"a"}]}
            ]}]}
        """.trimIndent()
        val e = DetectorEngine(RulesLoader.parse(json))
        val d = e.detect(FakeSnapshot("p", listOf(FakeNode(viewId = "a"))), setOf("instagram"))
        assertEquals("first", (d as Detection.Single).rule)
    }

    @Test
    fun `descAnyOf ignores case`() {
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(viewId = "reel_recycler"), FakeNode(contentDescription = "SHORTS", isSelected = true)),
        )
        assertEquals(Detection.Feed("youtube", "shorts_tab"), engine.detect(snap, all))
    }

    @Test
    fun `allOf and anyOf must both match`() {
        // Shorts tab selected but no player: nothing.
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(contentDescription = "Shorts", isSelected = true)),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
    }

    @Test
    fun `minHeightFraction tells the full-screen player apart`() {
        val tab = FakeNode(contentDescription = "Reels", isSelected = true)
        val small = FakeSnapshot("com.facebook.katana", listOf(tab, FakeNode(isScrollable = true, heightPx = 600)))
        val large = FakeSnapshot("com.facebook.katana", listOf(tab, FakeNode(isScrollable = true, heightPx = 2300)))
        assertEquals(Detection.None, engine.detect(small, all))
        assertEquals(Detection.Feed("facebook", "reels_tab"), engine.detect(large, all))
    }

    @Test
    fun `an app disabled in the settings returns None`() {
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.None, engine.detect(snap, all - "tiktok"))
        assertEquals(Detection.None, engine.detect(snap, emptySet()))
    }

    @Test
    fun `a malformed rules file loads no rules and reports an error`() {
        val errors = mutableListOf<String>()
        val parsed = RulesLoader.parse("{ this is not json") { errors += it }
        assertEquals(RulesFile.EMPTY, parsed)
        assertEquals(1, errors.size)
        val snap = FakeSnapshot("com.zhiliaoapp.musically", emptyList())
        assertEquals(Detection.None, DetectorEngine(parsed).detect(snap, all))
    }

    @Test
    fun `an unknown mode or exitAction makes the file invalid`() {
        val badMode = """{"version":1,"apps":[{"id":"x","packages":["p"],"rules":[{"name":"r","mode":"feeed","always":true}]}]}"""
        val badExit = """{"version":1,"apps":[{"id":"x","packages":["p"],"exitAction":"sideways","rules":[]}]}"""
        for (json in listOf(badMode, badExit)) {
            val errors = mutableListOf<String>()
            assertEquals(RulesFile.EMPTY, RulesLoader.parse(json) { errors += it })
            assertEquals(1, errors.size)
        }
    }

    // ------------------------------------------- evaluation order: conditions with a viewId first

    @Test
    fun `without the Shorts player the tree is not walked`() {
        // YouTube home: the Shorts tab is not selected and there is no player.
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(contentDescription = "Home", isSelected = true), FakeNode(contentDescription = "Shorts")),
        )
        assertEquals(Detection.None, engine.detect(snap, all))
        assertEquals(0, snap.walks)
    }

    @Test
    fun `with the Shorts player the tree is walked only once`() {
        val snap = FakeSnapshot(
            "com.google.android.youtube",
            listOf(FakeNode(viewId = "reel_recycler"), FakeNode(contentDescription = "Shorts", isSelected = true)),
        )
        assertEquals(Detection.Feed("youtube", "shorts_tab"), engine.detect(snap, all))
        assertEquals(1, snap.walks)
    }

    @Test
    fun `a failing allOf condition with a viewId avoids the walk even if anyOf is slow`() {
        val json = """
            {"version":1,"apps":[{"id":"instagram","packages":["p"],"rules":[
              {"name":"r","mode":"feed","allOf":[{"viewId":"a"}],"anyOf":[{"descAnyOf":["X"]}]}
            ]}]}
        """.trimIndent()
        val e = DetectorEngine(RulesLoader.parse(json))

        val withoutA = FakeSnapshot("p", listOf(FakeNode(contentDescription = "X")))
        assertEquals(Detection.None, e.detect(withoutA, setOf("instagram")))
        assertEquals(0, withoutA.walks)

        val withA = FakeSnapshot("p", listOf(FakeNode(viewId = "a"), FakeNode(contentDescription = "X")))
        assertEquals(Detection.Feed("instagram", "r"), e.detect(withA, setOf("instagram")))

        val withAWithoutX = FakeSnapshot("p", listOf(FakeNode(viewId = "a"), FakeNode(contentDescription = "Y")))
        assertEquals(Detection.None, e.detect(withAWithoutX, setOf("instagram")))
    }
}
