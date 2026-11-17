package app.bloccareels.block

import app.bloccareels.data.Settings
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The blocking state machine. Plain Kotlin: every side effect goes through [BlockActions],
 * timers are coroutines on [scope] and time comes from [clock], so tests run on virtual time.
 *
 * All methods must be called from the same thread (in the service: the main thread).
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
    var state: BlockState = BlockState.IDLE
        private set

    /** Host app the current state refers to (null in IDLE). */
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

    /** Container to watch for swipes; only in OBSERVING. */
    val observingPagerViewId: String?
        get() = if (state == BlockState.OBSERVING) observedPager else null

    /**
     * Nothing in progress: no active state, no block screen, audio untouched, no check pending after "Exit".
     * In this state [onForegroundLeft] does nothing.
     */
    val isIdle: Boolean
        get() = state == BlockState.IDLE && !exiting && !overlayShown && !muted

    // ---------------------------------------------------------------- events

    fun onDetection(d: Detection) {
        if (exiting) return
        when (state) {
            BlockState.IDLE -> when (d) {
                is Detection.Feed -> block(d.appId, "section detected (${d.rule})")
                is Detection.Single -> observe(d)
                Detection.None -> Unit
            }

            BlockState.OBSERVING -> when (d) {
                is Detection.Feed -> block(d.appId, "section detected (${d.rule})")
                is Detection.Single -> {
                    val stored = observedFingerprint
                    when {
                        d.appId != currentAppId -> observe(d)
                        stored == null -> observedFingerprint = d.fingerprint
                        d.fingerprint != null && d.fingerprint != stored -> block(d.appId, "swipe (video changed)")
                    }
                }
                Detection.None -> toIdle("video closed")
            }

            BlockState.BLOCKED -> when (d) {
                Detection.None -> {
                    noneStreak++
                    if (noneStreak >= NONE_STREAK_TO_LEAVE) {
                        hideAll()
                        toIdle("left short videos")
                    } else {
                        scheduleConfirm()
                    }
                }
                else -> {
                    noneStreak = 0
                    confirmJob?.cancel()
                }
            }

            // In TRUCE only the check when it expires counts.
            BlockState.TRUCE -> Unit
        }
    }

    fun onSwipe() {
        if (state == BlockState.OBSERVING) {
            currentAppId?.let { block(it, "swipe (scroll)") }
        }
    }

    fun onContinueTapped() {
        if (state != BlockState.BLOCKED) return
        if (clock() < continueEnabledAt - CONTINUE_TOLERANCE_MS) return
        hideAll()
        consecutiveContinues++
        safe("continue counter") { counters.onContinue() }
        setState(BlockState.TRUCE, "Continue")
        val truceMs = settings.value.truceSeconds.coerceAtLeast(1) * 1000L
        truceJob = scope.launch {
            delay(truceMs)
            onTruceExpired()
        }
    }

    fun onExitTapped() {
        if (state != BlockState.BLOCKED) return
        val action = currentExit
        hideAll()
        safe("exit counter") { counters.onExit() }
        safe("exit action") { actions.performExit(action) }
        exiting = true
        toIdle("Exit")
        exitJob = scope.launch {
            delay(EXIT_CHECK_MS)
            exiting = false
            val d = evaluate()
            if (d != Detection.None) {
                safe("home action") { actions.performExit(ExitAction.HOME) }
            }
        }
    }

    /** The foreground app is no longer a monitored host app. */
    fun onForegroundLeft() {
        if (isIdle) return
        reset("left the host app")
    }

    fun onScreenOff() = reset("screen off")

    fun onServiceStopped() = reset("service stopped")

    /** The master switch or the app's switch was turned off. */
    fun onDisabled() = reset("blocking disabled")

    // ------------------------------------------------------------- internals

    private fun onTruceExpired() {
        if (state != BlockState.TRUCE) return
        truceJob = null
        when (val d = evaluate()) {
            // When the truce expires a single video counts as a section: no new observation.
            is Detection.Feed -> block(d.appId, "truce over")
            is Detection.Single -> block(d.appId, "truce over")
            Detection.None -> toIdle("truce over, out of short videos")
        }
    }

    private fun observe(d: Detection.Single) {
        currentAppId = d.appId
        observedPager = d.pagerViewId
        observedFingerprint = d.fingerprint
        setState(BlockState.OBSERVING, "single video (${d.rule})")
    }

    private fun block(appId: String, reason: String) {
        val s = settings.value
        val now = clock()
        val meta = appMeta(appId)
        consecutiveContinues = DelayPolicy.consecutiveForNewBlock(consecutiveContinues, lastVisibleAt, now)
        val wait = DelayPolicy.waitSeconds(s.continueDelaySeconds, s.escalatingDelay, consecutiveContinues)

        // Level = number of consecutive "Continue" taps; after the last level it stays on the last one.
        val messages = s.messages.ifEmpty { listOf("") }
        val index = minOf(consecutiveContinues, messages.lastIndex)

        val blocks = try {
            counters.onBlockShown()
        } catch (e: Exception) {
            onError("block counter", e)
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

        safe("show block screen") { actions.showOverlay(model) }
        overlayShown = true
        // A custom video needs its sound: the player requests audio focus
        // (the app underneath pauses) instead of muting everything.
        if (s.muteAudio && !model.playsOwnAudio && !muted) {
            safe("mute audio") { actions.muteAudio() }
            muted = true
        }
        setState(BlockState.BLOCKED, reason)
    }

    /** Removes the block screen and restores the audio. Each step runs even if another one fails. */
    private fun hideAll() {
        confirmJob?.cancel()
        confirmJob = null
        if (overlayShown) {
            overlayShown = false
            lastVisibleAt = clock()
            safe("hide block screen") { actions.hideOverlay() }
        }
        if (muted) {
            muted = false
            safe("restore audio") { actions.restoreAudio() }
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
        setState(BlockState.IDLE, reason)
    }

    /** A single "None" in BLOCKED is not enough: check again shortly, even if no events arrive. */
    private fun scheduleConfirm() {
        if (confirmJob?.isActive == true) return
        confirmJob = scope.launch {
            delay(CONFIRM_NONE_MS)
            confirmJob = null
            if (state == BlockState.BLOCKED) onDetection(evaluate())
        }
    }

    private fun evaluate(): Detection =
        try {
            actions.evaluateNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("rule evaluation", e)
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
        /** After "Exit", how long to wait before checking that the user really left; if not, go Home. */
        const val EXIT_CHECK_MS = 600L

        /** After a single "None" in BLOCKED, how long to wait before checking again. */
        const val CONFIRM_NONE_MS = 300L

        /**
         * A tap on "Continue" up to this much early is accepted: the countdown on the block screen
         * and the controller's clock can differ by a few milliseconds.
         */
        const val CONTINUE_TOLERANCE_MS = 250L

        /** Consecutive "None" results that remove the block screen: one alone can happen during a UI transition. */
        const val NONE_STREAK_TO_LEAVE = 2
    }
}
