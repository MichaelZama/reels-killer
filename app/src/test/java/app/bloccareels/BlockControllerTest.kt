package app.bloccareels

import app.bloccareels.block.AppMeta
import app.bloccareels.block.BlockActions
import app.bloccareels.block.BlockController
import app.bloccareels.block.BlockState
import app.bloccareels.block.Counters
import app.bloccareels.block.OverlayModel
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

    private val feed = Detection.Feed("instagram", "scheda_reels")
    private val single = Detection.Single("instagram", "reel_singolo", "pager", fingerprint = 1)
    private val tiktok = Detection.Feed("tiktok", "tutta_la_app")

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
                    "tiktok" -> AppMeta("Sei su TikTok.", ExitAction.HOME)
                    else -> AppMeta("Sei nei Reels di Instagram.", ExitAction.BACK)
                }
            },
        )
        return Env(c, a, k, flow)
    }

    // ------------------------------------------------ transizioni della macchina a stati

    @Test
    fun `INATTIVO + Feed porta a BLOCCATO con schermata, audio muto e contatore`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        assertEquals(BlockState.BLOCCATO, e.c.state)
        assertTrue(e.a.visible)
        assertTrue(e.a.mutedNow)
        assertEquals(1, e.k.blocks)
        assertEquals("Sei nei Reels di Instagram.", e.a.shown!!.subtitle)
        assertEquals(1, e.a.shown!!.blocksToday)
    }

    @Test
    fun `INATTIVO + Single porta a OSSERVAZIONE senza schermata`() = runTest {
        val e = env()
        e.c.onDetection(single)
        assertEquals(BlockState.OSSERVAZIONE, e.c.state)
        assertFalse(e.a.visible)
        assertEquals("pager", e.c.observingPagerViewId)
    }

    @Test
    fun `OSSERVAZIONE + swipe porta a BLOCCATO`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onSwipe()
        assertEquals(BlockState.BLOCCATO, e.c.state)
        assertTrue(e.a.visible)
    }

    @Test
    fun `OSSERVAZIONE + cambio di impronta porta a BLOCCATO`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(single) // stesso video
        assertEquals(BlockState.OSSERVAZIONE, e.c.state)
        e.c.onDetection(single.copy(fingerprint = 2))
        assertEquals(BlockState.BLOCCATO, e.c.state)
    }

    @Test
    fun `OSSERVAZIONE + Feed porta a BLOCCATO`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(feed)
        assertEquals(BlockState.BLOCCATO, e.c.state)
    }

    @Test
    fun `OSSERVAZIONE + None torna INATTIVO`() = runTest {
        val e = env()
        e.c.onDetection(single)
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertNull(e.c.observingPagerViewId)
        assertFalse(e.a.visible)
    }

    @Test
    fun `BLOCCATO + Continua porta a TREGUA`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        assertEquals(BlockState.TREGUA, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
        assertEquals(1, e.k.continues)
    }

    @Test
    fun `BLOCCATO + Esci torna INATTIVO ed esegue exitAction`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onExitTapped()
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
        assertEquals(listOf(ExitAction.BACK), e.a.exits)
        assertEquals(1, e.k.exits)
        advanceTimeBy(BlockController.EXIT_CHECK_MS + 1)
        assertEquals(listOf(ExitAction.BACK), e.a.exits) // uscito davvero: niente HOME
    }

    @Test
    fun `Esci con rilevamento ancora presente dopo 600 ms esegue HOME`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = feed
        e.c.onExitTapped()
        // Durante l'attesa i rilevamenti vengono ignorati.
        e.c.onDetection(feed)
        assertFalse(e.a.visible)
        advanceTimeBy(BlockController.EXIT_CHECK_MS - 1)
        assertEquals(listOf(ExitAction.BACK), e.a.exits)
        advanceTimeBy(2)
        assertEquals(listOf(ExitAction.BACK, ExitAction.HOME), e.a.exits)
    }

    @Test
    fun `TikTok Esci porta alla Home`() = runTest {
        val e = env()
        e.c.onDetection(tiktok)
        e.c.onExitTapped()
        assertEquals(listOf(ExitAction.HOME), e.a.exits)
    }

    @Test
    fun `un solo None in BLOCCATO non chiude, due consecutivi si`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = feed
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.BLOCCATO, e.c.state)
        assertTrue(e.a.visible)
        // Il ricontrollo programmato trova ancora i Reels: resta.
        advanceTimeBy(BlockController.CONFIRM_NONE_MS + 1)
        assertEquals(BlockState.BLOCCATO, e.c.state)

        e.c.onDetection(Detection.None)
        e.c.onDetection(Detection.None)
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertFalse(e.a.visible)
        assertFalse(e.a.mutedNow)
    }

    @Test
    fun `None in BLOCCATO confermato dal ricontrollo chiude anche senza nuovi eventi`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.a.next = Detection.None
        e.c.onDetection(Detection.None)
        advanceTimeBy(BlockController.CONFIRM_NONE_MS + 1)
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertFalse(e.a.visible)
    }

    // ---------------------------------------------------------------- tregua

    @Test
    fun `la schermata torna esattamente alla scadenza della tregua se il rilevamento persiste`() = runTest {
        val e = env(Settings(messages = listOf("a", "b"), truceSeconds = 5, escalatingDelay = false))
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = feed
        // I rilevamenti durante la tregua sono ignorati.
        e.c.onDetection(feed)
        advanceTimeBy(4_999)
        assertFalse(e.a.visible)
        assertEquals(BlockState.TREGUA, e.c.state)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(e.a.visible)
        assertEquals(BlockState.BLOCCATO, e.c.state)
        assertEquals(2, e.k.blocks)
    }

    @Test
    fun `alla scadenza della tregua un Single vale come Feed`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = single
        advanceTimeBy(3_001)
        assertEquals(BlockState.BLOCCATO, e.c.state)
    }

    @Test
    fun `nessuna schermata se alla scadenza della tregua il rilevamento e None`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        e.a.next = Detection.None
        advanceTimeBy(10_000)
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertFalse(e.a.visible)
        assertEquals(1, e.k.blocks)
    }

    // ---------------------------------------------------- attesa su Continua

    @Test
    fun `Continua disattivato durante l'attesa non produce nulla`() = runTest {
        val e = env(Settings(messages = listOf("a"), continueDelaySeconds = 5))
        e.c.onDetection(feed)
        assertEquals(5, e.a.shown!!.waitSeconds)
        e.c.onContinueTapped()
        assertEquals(BlockState.BLOCCATO, e.c.state)
        advanceTimeBy(5_000)
        e.c.onContinueTapped()
        assertEquals(BlockState.TREGUA, e.c.state)
    }

    @Test
    fun `attesa crescente a ogni Continua consecutivo`() = runTest {
        val e = env(Settings(messages = listOf("a", "b"), truceSeconds = 3, escalatingDelay = true))
        e.a.next = feed
        val attese = mutableListOf<Int>()
        val hint = mutableListOf<Boolean>()
        e.c.onDetection(feed)
        repeat(4) {
            val m = e.a.shown!!
            attese += m.waitSeconds
            hint += m.showEscalationHint
            advanceTimeBy(m.waitSeconds * 1000L)
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf(0, 2, 5, 10), attese)
        assertEquals(listOf(false, true, true, true), hint)
    }

    @Test
    fun `Esci non azzera i Continua consecutivi, 10 minuti senza blocchi si`() = runTest {
        val e = env(Settings(messages = listOf("a"), escalatingDelay = true))
        e.a.next = feed
        e.c.onDetection(feed)
        e.c.onContinueTapped()
        advanceTimeBy(3_001) // torna il blocco, attesa 2 s
        assertEquals(2, e.a.shown!!.waitSeconds)
        advanceTimeBy(2_000)
        e.a.next = Detection.None
        e.c.onExitTapped()
        advanceTimeBy(1_000)
        e.c.onDetection(feed)
        assertEquals(2, e.a.shown!!.waitSeconds) // stessa sessione
        e.c.onExitTapped()
        advanceTimeBy(10 * 60 * 1000L)
        e.c.onDetection(feed)
        assertEquals(0, e.a.shown!!.waitSeconds) // sessione nuova
    }

    // ----------------------------------------------------------------- audio

    @Test
    fun `a ogni muteAudio corrisponde un restoreAudio in tutti i percorsi`() = runTest {
        val percorsi: List<Pair<String, (Env) -> Unit>> = listOf(
            "continua" to { e -> e.c.onContinueTapped() },
            "esci" to { e -> e.c.onExitTapped() },
            "none" to { e -> e.c.onDetection(Detection.None); e.c.onDetection(Detection.None) },
            "app lasciata" to { e -> e.c.onForegroundLeft() },
            "schermo spento" to { e -> e.c.onScreenOff() },
            "servizio fermato" to { e -> e.c.onServiceStopped() },
            "disattivato" to { e -> e.c.onDisabled() },
        )
        for ((nome, azione) in percorsi) {
            val e = env()
            e.c.onDetection(feed)
            azione(e)
            assertEquals("mute in $nome", 1, e.a.muteCount)
            assertEquals("restore in $nome", 1, e.a.restoreCount)
            assertFalse("audio ancora muto in $nome", e.a.mutedNow)
            assertFalse("schermata ancora visibile in $nome", e.a.visible)
        }
    }

    @Test
    fun `restoreAudio viene chiamato anche se hideOverlay fallisce`() = runTest {
        val e = env()
        val failing = object : BlockActions by e.a {
            override fun hideOverlay() {
                throw IllegalStateException("finestra sparita")
            }
        }
        val errors = mutableListOf<String>()
        val c = BlockController(failing, e.settings, e.k, backgroundScope, { testScheduler.currentTime }, { null },
            onError = { what, _ -> errors += what })
        c.onDetection(feed)
        c.onExitTapped()
        assertEquals(1, e.a.restoreCount)
        assertEquals(1, errors.size)
        assertEquals(BlockState.INATTIVO, c.state)
    }

    @Test
    fun `con l'opzione audio spenta muteAudio non viene mai chiamato`() = runTest {
        val e = env(Settings(messages = listOf("a"), muteAudio = false))
        e.c.onDetection(feed)
        e.c.onExitTapped()
        e.c.onDetection(feed)
        e.c.onScreenOff()
        assertEquals(0, e.a.muteCount)
        assertEquals(0, e.a.restoreCount)
    }

    @Test
    fun `con un video personalizzato non si silenzia l'audio`() = runTest {
        val video = app.bloccareels.data.CustomMedia("/x/video.mp4", app.bloccareels.data.MediaKind.VIDEO)
        val e = env(Settings(messages = listOf("a"), muteAudio = true, illustration = app.bloccareels.data.Illustration.PERSONALIZZATO, customMedia = video))
        e.c.onDetection(feed)
        assertEquals(video, e.a.shown!!.customMedia)
        assertEquals(0, e.a.muteCount)
        e.c.onExitTapped()
        assertEquals(0, e.a.restoreCount)
    }

    @Test
    fun `con un'immagine personalizzata l'audio viene silenziato`() = runTest {
        val img = app.bloccareels.data.CustomMedia("/x/foto.img", app.bloccareels.data.MediaKind.IMAGE)
        val e = env(Settings(messages = listOf("a"), muteAudio = true, illustration = app.bloccareels.data.Illustration.PERSONALIZZATO, customMedia = img))
        e.c.onDetection(feed)
        assertEquals(1, e.a.muteCount)
        e.c.onExitTapped()
        assertEquals(1, e.a.restoreCount)
    }

    // ------------------------------------- schermo spento e servizio fermato

    @Test
    fun `schermo spento e servizio fermato riportano a INATTIVO da ogni stato`() = runTest {
        val preparazioni: List<Pair<String, (Env) -> Unit>> = listOf(
            "INATTIVO" to { _ -> },
            "OSSERVAZIONE" to { e -> e.c.onDetection(single) },
            "BLOCCATO" to { e -> e.c.onDetection(feed) },
            "TREGUA" to { e -> e.c.onDetection(feed); e.c.onContinueTapped() },
        )
        for ((stato, prepara) in preparazioni) {
            for (spegni in listOf<(BlockController) -> Unit>({ it.onScreenOff() }, { it.onServiceStopped() })) {
                val e = env()
                e.a.next = feed
                prepara(e)
                assertEquals(stato, e.c.state.name)
                spegni(e.c)
                assertEquals(BlockState.INATTIVO, e.c.state)
                assertFalse(e.a.visible)
                assertFalse(e.a.mutedNow)
                // Nessun timer residuo deve rimostrare la schermata.
                advanceTimeBy(60_000)
                assertFalse("$stato: la schermata non deve tornare", e.a.visible)
            }
        }
    }

    // -------------------------------------------------------------- messaggi

    @Test
    fun `il livello segue i Continua consecutivi e resta sull'ultimo`() = runTest {
        val e = env(Settings(messages = listOf("a", "b", "c"), escalatingDelay = false))
        e.a.next = feed
        val visti = mutableListOf<String>()
        e.c.onDetection(feed)
        repeat(5) {
            visti += e.a.shown!!.message
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf("a", "b", "c", "c", "c"), visti)
    }

    @Test
    fun `dopo 10 minuti senza blocchi si riparte dal livello 1`() = runTest {
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
    fun `un livello senza media usa quello del livello precedente`() = runTest {
        val m1 = app.bloccareels.data.CustomMedia("/x/1.gif", app.bloccareels.data.MediaKind.GIF)
        val m3 = app.bloccareels.data.CustomMedia("/x/3.img", app.bloccareels.data.MediaKind.IMAGE)
        val s = Settings(
            messages = listOf("a", "b", "c", "d"),
            levelMedia = listOf(m1, null, m3, null),
            escalatingDelay = false,
        )
        val e = env(s)
        e.a.next = feed
        val visti = mutableListOf<app.bloccareels.data.CustomMedia?>()
        e.c.onDetection(feed)
        repeat(5) {
            visti += e.a.shown!!.customMedia
            e.c.onContinueTapped()
            advanceTimeBy(3_001)
        }
        assertEquals(listOf(m1, m1, m3, m3, m3), visti)
    }

    @Test
    fun `senza media nei livelli si usa il media generale oppure il disegno`() = runTest {
        val generale = app.bloccareels.data.CustomMedia("/x/g.img", app.bloccareels.data.MediaKind.IMAGE)
        val conGenerale = Settings(
            messages = listOf("a"),
            illustration = app.bloccareels.data.Illustration.PERSONALIZZATO,
            customMedia = generale,
        )
        assertEquals(generale, conGenerale.mediaForLevel(0))
        assertEquals(null, Settings(messages = listOf("a"), customMedia = generale).mediaForLevel(3))
    }

    // ------------------------------------- isIdle (il servizio scarta gli eventi delle altre app)

    @Test
    fun `isIdle e vero solo quando non c'e niente in corso`() = runTest {
        val e = env()
        assertTrue(e.c.isIdle)

        e.c.onDetection(single)
        assertFalse("OSSERVAZIONE", e.c.isIdle)
        e.c.onDetection(Detection.None)
        assertTrue(e.c.isIdle)

        e.c.onDetection(feed)
        assertFalse("BLOCCATO", e.c.isIdle)
        e.c.onContinueTapped()
        assertEquals(BlockState.TREGUA, e.c.state)
        assertFalse("TREGUA", e.c.isIdle)
        e.c.onForegroundLeft()
        assertTrue(e.c.isIdle)
    }

    @Test
    fun `dopo Esci isIdle resta falso finche non finisce il controllo`() = runTest {
        val e = env()
        e.c.onDetection(feed)
        e.c.onExitTapped()
        assertEquals(BlockState.INATTIVO, e.c.state)
        assertFalse(e.c.isIdle)
        advanceTimeBy(BlockController.EXIT_CHECK_MS + 1)
        assertTrue(e.c.isIdle)
    }
}
