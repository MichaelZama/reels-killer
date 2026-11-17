package app.bloccareels

import app.bloccareels.block.AppMeta
import app.bloccareels.block.BlockActions
import app.bloccareels.block.BlockController
import app.bloccareels.block.BlockState
import app.bloccareels.block.Counters
import app.bloccareels.block.OverlayModel
import app.bloccareels.data.CustomMedia
import app.bloccareels.data.Illustration
import app.bloccareels.data.MediaKind
import app.bloccareels.data.Settings
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeActions : BlockActions {
    val calls = mutableListOf<String>()
    var shown: OverlayModel? = null
    var visible = false
    var mutedNow = false
    var muteCount = 0
    var restoreCount = 0
    var exits = mutableListOf<ExitAction>()
    var next: Detection = Detection.None

    override fun showOverlay(model: OverlayModel) { calls += "show"; shown = model; visible = true }
    override fun hideOverlay() { calls += "hide"; visible = false }
    override fun muteAudio() { calls += "mute"; muteCount++; mutedNow = true }
    override fun restoreAudio() { calls += "restore"; restoreCount++; mutedNow = false }
    override fun performExit(action: ExitAction) { calls += "exit:$action"; exits += action }
    override fun evaluateNow(): Detection = next
}

private class FakeCounters : Counters {
    var blocks = 0
    var continues = 0
    var exits = 0
    override fun onBlockShown(): Int = ++blocks
    override fun onContinue() { continues++ }
    override fun onExit() { exits++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BlockControllerTest {

    private val feed = Detection.Feed("instagram", "reels_tab")
    private val single = Detection.Single("instagram", "single_reel", "pager", fingerprint = 1)
    private val tiktok = Detection.Feed("tiktok", "whole_app")

    private class Env(
        val c: BlockController,
        val a: FakeActions,
        val k: FakeCounters,
        val settings: MutableStateFlow<Settings>,
    )

    private fun TestScope.env(settings: Settings = Settings(messages = listOf("m1", "m2", "m3"))): Env {
        val a = FakeActions()
        val k = FakeCounters()
        val flow = MutableStateFlow(settings)
        val c = BlockController(
            actions = a,
            settings = flow,
            counters = k,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            appMeta = { id ->
                when (id) {
                    "tiktok" -> AppMeta("You are on TikTok.", ExitAction.HOME)
                    else -> AppMeta("You are in Instagram Reels.", ExitAction.BACK)
                }
            },
        )
        return Env(c, a, k, flow)
    }

    // ------------------------------------------------------ state machine transitions

    @Test
    fun `IDLE + Feed goes to BLOCKED with block screen, muted audio and counter`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        assertEquals(BlockState.BLOCKED, e.c.state)
        assertTrue(e.a.visible)
        assertTrue(e.a.mutedNow)
        assertEquals(1, e.k.blocks)
        assertEquals("You are in Instagram Reels.", e.a.shown!!.subtitle)
        assertEquals(1, e.a.shown!!.blocksToday)
    }

    @Test
    fun `IDLE + Single goes to OBSERVING without block screen`() = runTest {
        val e = env()
        e.c.onDetection(single)
        assertEquals(BlockState.OBSERVING, e.c.state)
        assertFalse(e.a.visible)
        assertEquals("pager", e.c.observingPagerViewId)
    }

    @Test
    fun `OBSERVING + swipe goes to BLOCKED`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onSwipe()
        assertEquals(BlockState.BLOCKED, e.c.state)
        assertTrue(e.a.visible)
    }

    @Test
    fun `OBSERVING + fingerprint change goes to BLOCKED`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(single) // same video
        assertEquals(BlockState.OBSERVING, e.c.state)
        e.c.onDetection(single.copy(fingerprint = 2))
        assertEquals(BlockState.BLOCKED, e.c.state)
    }

    @Test
    fun `OBSERVING + Feed goes to BLOCKED`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(feed)
        assertEquals(BlockState.BLOCKED, e.c.state)
    }

    @Test
    fun `OBSERVING + None goes back to IDLE`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.IDLE, e.c.state)
        assertNull(e.c.observingPagerViewId)
        assertFalse(e.a.visible)
    }

    @Test
    fun `BLOCKED + Continue goes to TRUCE`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        assertEquals(BlockState.TRUCE, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
        assertEquals(1, e.k.continues)
    }

    @Test
    fun `BLOCKED + Exit goes back to IDLE and performs exitAction`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onExitTapped()
        assertEquals(BlockState.IDLE, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
        assertEquals(listOf(ExitAction.BACK), e.a.exits)
        assertEquals(1, e.k.exits)
        advanceTimeBy(BlockController.EXIT_CHECK_MS + 1)
        assertEquals(listOf(ExitAction.BACK), e.a.exits) // really left: no HOME
    }

    @Test
    fun `Exit with the detection still there after 600 ms performs HOME`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = feed
        e.c.onExitTapped()
        // Detections are ignored while waiting.
        e.c.onDetection(feed)
        assertFalse(e.a.visible)
        advanceTimeBy(BlockController.EXIT_CHECK_MS - 1)
        assertEquals(listOf(ExitAction.BACK), e.a.exits)
        advanceTimeBy(2)
        assertEquals(listOf(ExitAction.BACK, ExitAction.HOME), e.a.exits)
    }

    @Test
    fun `TikTok Exit goes Home`() = runTest {
        val e = env()
        e.c.onDetection(tiktok)
        e.c.onExitTapped()
        assertEquals(listOf(ExitAction.HOME), e.a.exits)
    }

    @Test
    fun `a single None in BLOCKED does not close, two in a row do`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = feed
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.BLOCKED, e.c.state)
        assertTrue(e.a.visible)
        // The scheduled recheck still finds Reels: it stays.
        advanceTimeBy(BlockController.CONFIRM_NONE_MS + 1)
        assertEquals(BlockState.BLOCKED, e.c.state)

        e.c.onDetection(Detection.None)
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.IDLE, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
    }

    @Test
    fun `None in BLOCKED confirmed by the recheck closes even without new events`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = Detection.None
        e.c.onDetection(Detection.None)
        advanceTimeBy(BlockController.CONFIRM_NONE_MS + 1)
        assertEquals(BlockState.IDLE, e.c.state)
        assertFalse(e.a.visible)
    }

    // ----------------------------------------------------------------- truce

    @Test
    fun `the block screen comes back exactly when the truce expires if the detection persists`() = runTest {
        val e = env(Settings(messages = listOf("a", "b"), truceSeconds = 5, escalatingDelay = false))
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = feed
        // Detections during the truce are ignored.
        e.c.onDetection(feed)
        advanceTimeBy(4_999)
        assertFalse(e.a.visible)
        assertEquals(BlockState.TRUCE, e.c.state)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(e.a.visible)
        assertEquals(BlockState.BLOCKED, e.c.state)
        assertEquals(2, e.k.blocks)
    }

    @Test
    fun `when the truce expires a Single counts as Feed`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = single
        advanceTimeBy(3_001)
        assertEquals(BlockState.BLOCKED, e.c.state)
    }

    @Test
    fun `no block screen if the detection is None when the truce expires`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = Detection.None
        advanceTimeBy(10_000)
        assertEquals(BlockState.IDLE, e.c.state)
        assertFalse(e.a.visible)
        assertEquals(1, e.k.blocks)
    }

    // ------------------------------------------------------ wait on Continue

    @Test
    fun `Continue tapped during the wait does nothing`() = runTest {
        val e = env(Settings(messages = listOf("a"), continueDelaySeconds = 5))
        e.c.onDetection(feed)
        assertEquals(5, e.a.shown!!.waitSeconds)
        e.c.onContinueTapped()
        assertEquals(BlockState.BLOCKED, e.c.state)
        advanceTimeBy(5_000)
        e.c.onContinueTapped()
        assertEquals(BlockState.TRUCE, e.c.state)
    }

    @Test
    fun `the wait grows with each consecutive Continue`() = runTest {
        val e = env(Settings(messages = listOf("a", "b"), truceSeconds = 3, escalatingDelay = true))
        e.a.next = feed
        val waits = mutableListOf<Int>()
        val hint = mutableListOf<Boolean>()
        e.c.onDetection(feed)
        repeat(4) {
            val m = e.a.shown!!
            waits += m.waitSeconds
            hint += m.showEscalationHint
            advanceTimeBy(m.waitSeconds * 1000L)
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf(0, 2, 5, 10), waits)
        assertEquals(listOf(false, true, true, true), hint)
    }

    @Test
    fun `Exit does not reset consecutive Continues, 10 minutes without blocks does`() = runTest {
        val e = env(Settings(messages = listOf("a"), escalatingDelay = true))
        e.a.next = feed
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        advanceTimeBy(3_001) // the block comes back, 2 s wait
        assertEquals(2, e.a.shown!!.waitSeconds)
        advanceTimeBy(2_000)
        e.a.next = Detection.None
        e.c.onExitTapped()
        advanceTimeBy(1_000)
        e.c.onDetection(feed)
        assertEquals(2, e.a.shown!!.waitSeconds) // same session
        e.c.onExitTapped()
        advanceTimeBy(10 * 60 * 1000L)
        e.c.onDetection(feed)
        assertEquals(0, e.a.shown!!.waitSeconds) // new session
    }

    // ----------------------------------------------------------------- audio

    @Test
    fun `every muteAudio is matched by a restoreAudio on every path`() = runTest {
        val paths: List<Pair<String, (Env) -> Unit>> = listOf(
            "continue" to { e -> e.c.onContinueTapped() },
            "exit" to { e -> e.c.onExitTapped() },
            "none" to { e -> e.c.onDetection(Detection.None); e.c.onDetection(Detection.None) },
            "app left" to { e -> e.c.onForegroundLeft() },
            "screen off" to { e -> e.c.onScreenOff() },
            "service stopped" to { e -> e.c.onServiceStopped() },
            "disabled" to { e -> e.c.onDisabled() },
        )
        for ((name, action) in paths) {
            val e = env()
            e.c.onDetection(feed)
            action(e)
            assertEquals("mute in $name", 1, e.a.muteCount)
            assertEquals("restore in $name", 1, e.a.restoreCount)
            assertFalse("audio still muted in $name", e.a.mutedNow)
            assertFalse("block screen still visible in $name", e.a.visible)
        }
    }

    @Test
    fun `restoreAudio is called even if hideOverlay fails`() = runTest {
        val e = env()
        val failing = object : BlockActions by e.a {
            override fun hideOverlay() {
                throw IllegalStateException("window gone")
            }
        }
        val errors = mutableListOf<String>()
        val c = BlockController(failing, e.settings, e.k, backgroundScope, { testScheduler.currentTime }, { null },
            onError = { what, _ -> errors += what })
        c.onDetection(feed)
        c.onExitTapped()
        assertEquals(1, e.a.restoreCount)
        assertEquals(1, errors.size)
        assertEquals(BlockState.IDLE, c.state)
    }

    @Test
    fun `with the audio option off muteAudio is never called`() = runTest {
        val e = env(Settings(messages = listOf("a"), muteAudio = false))
        e.c.onDetection(feed)
        e.c.onExitTapped()
        e.c.onDetection(feed)
        e.c.onScreenOff()
        assertEquals(0, e.a.muteCount)
        assertEquals(0, e.a.restoreCount)
    }

    @Test
    fun `with a custom video the audio is not muted`() = runTest {
        val video = CustomMedia("/x/video.mp4", MediaKind.VIDEO)
        val e = env(Settings(messages = listOf("a"), muteAudio = true, illustration = Illustration.CUSTOM, customMedia = video))
        e.c.onDetection(feed)
        assertEquals(video, e.a.shown!!.customMedia)
        assertEquals(0, e.a.muteCount)
        e.c.onExitTapped()
        assertEquals(0, e.a.restoreCount)
    }

    @Test
    fun `with a custom image the audio is muted`() = runTest {
        val img = CustomMedia("/x/photo.img", MediaKind.IMAGE)
        val e = env(Settings(messages = listOf("a"), muteAudio = true, illustration = Illustration.CUSTOM, customMedia = img))
        e.c.onDetection(feed)
        assertEquals(1, e.a.muteCount)
        e.c.onExitTapped()
        assertEquals(1, e.a.restoreCount)
    }

    // ------------------------------------------------ screen off and service stopped

    @Test
    fun `screen off and service stopped go back to IDLE from every state`() = runTest {
        val setups: List<Pair<String, (Env) -> Unit>> = listOf(
            "IDLE" to { _ -> },
            "OBSERVING" to { e -> e.c.onDetection(single) },
            "BLOCKED" to { e -> e.c.onDetection(feed) },
            "TRUCE" to { e -> e.c.onDetection(feed); e.c.onContinueTapped() },
        )
        for ((state, prepare) in setups) {
            for (stop in listOf<(BlockController) -> Unit>({ it.onScreenOff() }, { it.onServiceStopped() })) {
                val e = env()
                e.a.next = feed
                prepare(e)
                assertEquals(state, e.c.state.name)
                stop(e.c)
                assertEquals(BlockState.IDLE, e.c.state)
                assertFalse(e.a.visible)
                assertFalse(e.a.mutedNow)
                // No leftover timer may show the block screen again.
                advanceTimeBy(60_000)
                assertFalse("$state: the block screen must not come back", e.a.visible)
            }
        }
    }

    // ---------------------------------------------------------------- levels

    @Test
    fun `the level follows consecutive Continues and stays on the last one`() = runTest {
        val e = env(Settings(messages = listOf("a", "b", "c"), escalatingDelay = false))
        e.a.next = feed
        val seen = mutableListOf<String>()
        e.c.onDetection(feed)
        repeat(5) {
            seen += e.a.shown!!.message
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf("a", "b", "c", "c", "c"), seen)
    }

    @Test
    fun `after 10 minutes without blocks it starts again from level 1`() = runTest {
        val e = env(Settings(messages = listOf("a", "b"), escalatingDelay = false))
        e.a.next = feed
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        advanceTimeBy(3_001)
        assertEquals("b", e.a.shown!!.message)
        e.a.next = Detection.None
        e.c.onExitTapped()
        advanceTimeBy(10 * 60 * 1000L)
        e.c.onDetection(feed)
        assertEquals("a", e.a.shown!!.message)
    }

    @Test
    fun `a level without media uses the previous level's`() = runTest {
        val m1 = CustomMedia("/x/1.gif", MediaKind.GIF)
        val m3 = CustomMedia("/x/3.img", MediaKind.IMAGE)
        val s = Settings(
            messages = listOf("a", "b", "c", "d"),
            levelMedia = listOf(m1, null, m3, null),
            escalatingDelay = false,
        )
        val e = env(s)
        e.a.next = feed
        val seen = mutableListOf<CustomMedia?>()
        e.c.onDetection(feed)
        repeat(5) {
            seen += e.a.shown!!.customMedia
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf(m1, m1, m3, m3, m3), seen)
    }

    @Test
    fun `without level media the general media or the drawing is used`() = runTest {
        val general = CustomMedia("/x/g.img", MediaKind.IMAGE)
        val withGeneral = Settings(
            messages = listOf("a"),
            illustration = Illustration.CUSTOM,
            customMedia = general,
        )
        assertEquals(general, withGeneral.mediaForLevel(0))
        assertEquals(null, Settings(messages = listOf("a"), customMedia = general).mediaForLevel(3))
    }

    // --------------------------------- isIdle (the service drops the other apps' events)

    @Test
    fun `isIdle is true only when nothing is in progress`() = runTest {
        val e = env()
        assertTrue(e.c.isIdle)

        e.c.onDetection(single)
        assertFalse("OBSERVING", e.c.isIdle)
        e.c.onDetection(Detection.None)
        assertTrue(e.c.isIdle)

        e.c.onDetection(feed)
        assertFalse("BLOCKED", e.c.isIdle)
        e.c.onContinueTapped()
        assertEquals(BlockState.TRUCE, e.c.state)
        assertFalse("TRUCE", e.c.isIdle)
        e.c.onForegroundLeft()
        assertTrue(e.c.isIdle)
    }

    @Test
    fun `after Exit isIdle stays false until the check is over`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onExitTapped()
        assertEquals(BlockState.IDLE, e.c.state)
        assertFalse(e.c.isIdle)
        advanceTimeBy(BlockController.EXIT_CHECK_MS + 1)
        assertTrue(e.c.isIdle)
    }
}
