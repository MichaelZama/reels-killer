package app.bloccareels.block

import app.bloccareels.data.Settings
import app.bloccareels.detect.Detection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Macchina a stati del blocco (sezione 7). Kotlin puro: tutti gli effetti passano da [BlockActions],
 * i timer sono coroutine su [scope] e il tempo arriva da [clock], così nei test si usa il tempo virtuale.
 *
 * Tutti i metodi vanno chiamati dallo stesso thread (nel servizio: il thread principale).
 */
class BlockController(
    private val actions: BlockActions,
    private val settings: StateFlow<Settings>,
    private val counters: Counters,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val appMeta: (appId: String) -> AppMeta?,
    private val onTransition: (from: BlockState, to: BlockState, reason: String) -> Unit = { _, _, _ -> },
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    var state: BlockState = BlockState.INATTIVO
        private set

    /** App ospite a cui si riferisce lo stato corrente (null in INATTIVO). */
    var currentAppId: String? = null
        private set

    private var currentExit: ExitAction = ExitAction.BACK
    private var observedPager: String? = null
    private var observedFingerprint: Int? = null
    private var noneStreak = 0

    private var consecutiveContinues = 0
    private var lastVisibleAt: Long? = null
    private var continueEnabledAt = 0L

    private var overlayShown = false
    private var muted = false
    private var exiting = false

    private var truceJob: Job? = null
    private var exitJob: Job? = null
    private var confirmJob: Job? = null

    /** Contenitore da osservare per lo swipe, solo in OSSERVAZIONE. */
    val observingPagerViewId: String?
        get() = if (state == BlockState.OSSERVAZIONE) observedPager else null

    val consecutiveContinueCount: Int get() = consecutiveContinues

    // ---------------------------------------------------------------- eventi

    fun onDetection(d: Detection) {
        if (exiting) return
        when (state) {
            BlockState.INATTIVO -> when (d) {
                is Detection.Feed -> block(d.appId, "rilevata sezione (${d.rule})")
                is Detection.Single -> observe(d)
                Detection.None -> Unit
            }

            BlockState.OSSERVAZIONE -> when (d) {
                is Detection.Feed -> block(d.appId, "rilevata sezione (${d.rule})")
                is Detection.Single -> {
                    val stored = observedFingerprint
                    when {
                        d.appId != currentAppId -> observe(d)
                        stored == null -> observedFingerprint = d.fingerprint
                        d.fingerprint != null && d.fingerprint != stored -> block(d.appId, "swipe (cambio di video)")
                    }
                }
                Detection.None -> toIdle("video chiuso")
            }

            BlockState.BLOCCATO -> when (d) {
                Detection.None -> {
                    noneStreak++
                    if (noneStreak >= 2) {
                        hideAll()
                        toIdle("uscito dai video brevi")
                    } else {
                        scheduleConfirm()
                    }
                }
                else -> {
                    noneStreak = 0
                    confirmJob?.cancel()
                }
            }

            // In TREGUA conta solo il controllo alla scadenza.
            BlockState.TREGUA -> Unit
        }
    }

    fun onSwipe() {
        if (state == BlockState.OSSERVAZIONE) {
            currentAppId?.let { block(it, "swipe (scorrimento)") }
        }
    }

    fun onContinueTapped() {
        if (state != BlockState.BLOCCATO) return
        if (clock() < continueEnabledAt - CONTINUE_TOLERANCE_MS) return
        hideAll()
        consecutiveContinues++
        safe("contatore continua") { counters.onContinue() }
        setState(BlockState.TREGUA, "Continua")
        val truceMs = settings.value.truceSeconds.coerceAtLeast(1) * 1000L
        truceJob = scope.launch {
            delay(truceMs)
            onTruceExpired()
        }
    }

    fun onExitTapped() {
        if (state != BlockState.BLOCCATO) return
        val action = currentExit
        hideAll()
        safe("contatore esci") { counters.onExit() }
        safe("azione esci") { actions.performExit(action) }
        exiting = true
        toIdle("Esci")
        exitJob = scope.launch {
            delay(EXIT_CHECK_MS)
            exiting = false
            val d = evaluate()
            if (d != Detection.None) {
                safe("azione home") { actions.performExit(ExitAction.HOME) }
            }
        }
    }

    /** L'app in primo piano non è più un'app ospite monitorata (UC-08). */
    fun onForegroundLeft() {
        if (state == BlockState.INATTIVO && !exiting && !overlayShown && !muted) return
        reset("uscito dall'app ospite")
    }

    fun onScreenOff() = reset("schermo spento")

    fun onServiceStopped() = reset("servizio fermato")

    /** Interruttore generale o dell'app spento. */
    fun onDisabled() = reset("blocco disattivato")

    // --------------------------------------------------------------- interni

    private fun onTruceExpired() {
        if (state != BlockState.TREGUA) return
        truceJob = null
        when (val d = evaluate()) {
            // Alla scadenza un video singolo vale come sezione: niente nuova osservazione.
            is Detection.Feed -> block(d.appId, "fine tregua")
            is Detection.Single -> block(d.appId, "fine tregua")
            Detection.None -> toIdle("fine tregua, fuori dai video brevi")
        }
    }

    private fun observe(d: Detection.Single) {
        currentAppId = d.appId
        observedPager = d.pagerViewId
        observedFingerprint = d.fingerprint
        setState(BlockState.OSSERVAZIONE, "video singolo (${d.rule})")
    }

    private fun block(appId: String, reason: String) {
        val s = settings.value
        val now = clock()
        val meta = appMeta(appId)
        consecutiveContinues = DelayPolicy.consecutiviAggiornati(consecutiveContinues, lastVisibleAt, now)
        val wait = DelayPolicy.attesa(s.continueDelaySeconds, s.escalatingDelay, consecutiveContinues)

        // Livello = numero di "Continua" consecutivi; finiti i livelli si resta sull'ultimo.
        val messages = s.messages.ifEmpty { listOf("") }
        val index = minOf(consecutiveContinues, messages.lastIndex)
        safe("indice messaggio") { counters.onMessageShown(index) }

        val blocks = try {
            counters.onBlockShown()
        } catch (e: Exception) {
            onError("contatore blocchi", e)
            s.blocksToday + 1
        }

        val model = OverlayModel(
            message = messages[index],
            subtitle = meta?.label.orEmpty(),
            illustration = s.illustration,
            blocksToday = blocks,
            waitSeconds = wait,
            showEscalationHint = s.escalatingDelay && consecutiveContinues >= 1,
            customMedia = s.mediaForLevel(index),
        )

        currentAppId = appId
        currentExit = meta?.exitAction ?: ExitAction.BACK
        observedPager = null
        observedFingerprint = null
        noneStreak = 0
        confirmJob?.cancel()
        continueEnabledAt = now + wait * 1000L
        lastVisibleAt = now

        safe("mostra schermata") { actions.showOverlay(model) }
        overlayShown = true
        // Con un video personalizzato l'audio serve: il player chiede il focus audio
        // (l'app sotto si mette in pausa) invece di silenziare tutto.
        if (s.muteAudio && !model.playsOwnAudio && !muted) {
            safe("silenzia audio") { actions.muteAudio() }
            muted = true
        }
        setState(BlockState.BLOCCATO, reason)
    }

    /** Toglie la schermata e ripristina l'audio. Ogni passo è indipendente dagli errori degli altri. */
    private fun hideAll() {
        confirmJob?.cancel()
        confirmJob = null
        if (overlayShown) {
            overlayShown = false
            lastVisibleAt = clock()
            safe("nascondi schermata") { actions.hideOverlay() }
        }
        if (muted) {
            muted = false
            safe("ripristina audio") { actions.restoreAudio() }
        }
    }

    private fun reset(reason: String) {
        truceJob?.cancel(); truceJob = null
        exitJob?.cancel(); exitJob = null
        exiting = false
        hideAll()
        toIdle(reason)
    }

    private fun toIdle(reason: String) {
        currentAppId = null
        observedPager = null
        observedFingerprint = null
        noneStreak = 0
        setState(BlockState.INATTIVO, reason)
    }

    /** Un solo "Nessuno" in BLOCCATO non basta: si ricontrolla poco dopo anche se non arrivano eventi. */
    private fun scheduleConfirm() {
        if (confirmJob?.isActive == true) return
        confirmJob = scope.launch {
            delay(CONFIRM_NONE_MS)
            confirmJob = null
            if (state == BlockState.BLOCCATO) onDetection(evaluate())
        }
    }

    private fun evaluate(): Detection =
        try {
            actions.evaluateNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("valutazione regole", e)
            Detection.None
        }

    private fun setState(to: BlockState, reason: String) {
        val from = state
        state = to
        if (from != to) onTransition(from, to, reason)
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(what, e)
        }
    }

    companion object {
        const val EXIT_CHECK_MS = 600L
        const val CONFIRM_NONE_MS = 300L
        const val CONTINUE_TOLERANCE_MS = 250L
    }
}
