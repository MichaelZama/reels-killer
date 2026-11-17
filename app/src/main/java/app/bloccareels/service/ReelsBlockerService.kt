package app.bloccareels.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.ServiceCommand
import app.bloccareels.block.AppMeta
import app.bloccareels.block.BlockActions
import app.bloccareels.block.BlockController
import app.bloccareels.block.OverlayModel
import app.bloccareels.container
import app.bloccareels.data.Locales
import app.bloccareels.data.Settings
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction
import app.bloccareels.detect.describe
import app.bloccareels.diag.DiagnosticsLog
import app.bloccareels.diag.LogType
import app.bloccareels.diag.TreeDumper
import app.bloccareels.overlay.AudioDucker
import app.bloccareels.overlay.OverlayManager
import app.bloccareels.overlay.SystemMusicStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The accessibility service: receives events, throttles them, evaluates the rules
 * and carries out the commands of the [BlockController].
 */
class ReelsBlockerService : AccessibilityService() {

    private val scope = MainScope()
    private lateinit var appContainer: AppContainer
    private lateinit var log: DiagnosticsLog
    private lateinit var overlay: OverlayManager
    private lateinit var ducker: AudioDucker
    private var controller: BlockController? = null
    private val statusNotification = StatusNotification(this, ::localized, ::logError)

    private var lastEvalAt = 0L
    private var pendingEval: Job? = null
    private var lastForegroundPackage: String? = null
    private var lastDetection: String? = null
    private var imePackages: Set<String> = emptySet()
    private var receiversRegistered = false

    private val settings: StateFlow<Settings> get() = appContainer.settings.state

    // -------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        applicationContext.container.diagnostics.add(LogType.EVENT, "Service created by the system")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContainer = applicationContext.container
        log = appContainer.diagnostics
        log.add(LogType.EVENT, "Service connecting…")
        ducker = AudioDucker(SystemMusicStream(getSystemService(AudioManager::class.java)), AudioDucker.markerFile(this))
        overlay = OverlayManager(this, scope, log) { settings.value.language }

        val ctrl = BlockController(
            actions = Actions(),
            settings = settings,
            counters = RepoCounters(appContainer.settings, scope),
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            appMeta = { id ->
                appContainer.engine.appById(id)?.let { AppMeta(localizedSubtitle(id) ?: it.label, it.exitAction) }
            },
            onTransition = { from, to, reason ->
                log.add(LogType.TRANSITION, "$from → $to ($reason)")
                log.updateStatus { it.copy(controllerState = to.name) }
            },
            onError = { what, e -> log.error(what, e) },
        )
        controller = ctrl
        overlay.onExit = { guarded("Exit") { ctrl.onExitTapped() } }
        overlay.onContinue = { guarded("Continue") { ctrl.onContinueTapped() } }

        imePackages = loadImePackages()
        registerReceivers()
        scope.launch { settings.collect { s -> onSettingsChanged(s) } }
        scope.launch { appContainer.serviceCommands.collect { cmd -> guarded("command") { onCommand(cmd) } } }

        log.updateStatus { it.copy(serviceConnected = true, controllerState = ctrl.state.name) }
        log.add(LogType.EVENT, "Service connected. Rules version ${appContainer.rules.version}, apps: ${appContainer.rules.apps.size}")
    }

    override fun onInterrupt() {
        guarded("onInterrupt") { controller?.onServiceStopped() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopEverything("service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopEverything("service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun stopEverything(reason: String) {
        guarded("stop") { controller?.onServiceStopped() }
        controller = null
        if (::overlay.isInitialized) guarded("overlay removal") { overlay.removeAllNow() }
        if (::ducker.isInitialized) guarded("audio restore") { ducker.restore() }
        if (receiversRegistered) {
            guarded("receiver") { unregisterReceiver(receiver) }
            receiversRegistered = false
        }
        statusNotification.cancel()
        if (::log.isInitialized) {
            log.updateStatus { it.copy(serviceConnected = false, controllerState = "IDLE") }
            log.add(LogType.EVENT, reason.replaceFirstChar { it.uppercase() })
        }
    }

    // ----------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        guarded("event") { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val ctrl = controller ?: return
        if (!appContainer.settings.loaded.value) return
        val s = settings.value
        if (!s.masterEnabled) return

        val evPkg = event.packageName?.toString()
        if (evPkg == packageName || evPkg == SYSTEM_UI || evPkg in imePackages) return

        val type = event.eventType

        // The service receives the events of every app. While nothing is in progress, for an app that
        // is not monitored only the window change matters (to notice when a monitored one is entered):
        // the other events are dropped without asking the system for the foreground window.
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && ctrl.isIdle &&
            !appContainer.engine.isMonitored(evPkg, s.enabledAppIds)
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()

        val fgPkg = foregroundRoot()?.packageName?.toString() ?: evPkg
        if (fgPkg != lastForegroundPackage) {
            lastForegroundPackage = fgPkg
            log.add(LogType.EVENT, "Foreground: ${fgPkg ?: "?"}")
        }
        log.updateStatus { it.copy(lastPackage = fgPkg, lastEventAtMs = System.currentTimeMillis()) }

        if (!appContainer.engine.isMonitored(fgPkg, s.enabledAppIds)) {
            pendingEval?.cancel()
            pendingEval = null
            publishDetection(Detection.None)
            ctrl.onForegroundLeft()
            return
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val pager = ctrl.observingPagerViewId
            if (pager != null) {
                val srcId = NodeAdapter.shortId(event.source?.viewIdResourceName)
                if (srcId == pager) {
                    log.add(LogType.EVENT, "Scroll of $pager")
                    ctrl.onSwipe()
                }
            }
        }

        // Throttling: window changes are evaluated at once, the rest at most every THROTTLE_MS.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastEvalAt >= THROTTLE_MS) {
            pendingEval?.cancel()
            pendingEval = null
            evaluateAndDispatch()
        } else if (pendingEval == null) {
            val wait = THROTTLE_MS - (now - lastEvalAt)
            pendingEval = scope.launch {
                delay(wait)
                pendingEval = null
                guarded("scheduled evaluation") { evaluateAndDispatch() }
            }
        }
    }

    private fun evaluateAndDispatch() {
        val ctrl = controller ?: return
        val d = evaluate()
        publishDetection(d)
        ctrl.onDetection(d)
    }

    /** Synchronous evaluation: used by the events and by the controller (truce over, check after Exit). */
    private fun evaluate(): Detection {
        lastEvalAt = SystemClock.elapsedRealtime()
        val s = settings.value
        if (!s.masterEnabled) return Detection.None
        val root = foregroundRoot() ?: return Detection.None
        val pkg = root.packageName?.toString()
        if (!appContainer.engine.isMonitored(pkg, s.enabledAppIds)) return Detection.None
        val snapshot = NodeSnapshot(root, resources.displayMetrics.heightPixels)
        return appContainer.engine.detect(snapshot, s.enabledAppIds)
    }

    private fun publishDetection(d: Detection) {
        val text = d.describe()
        if (text != lastDetection) {
            lastDetection = text
            log.add(LogType.DETECTION, text)
            log.updateStatus { it.copy(lastDetection = text) }
        }
    }

    /** Root of the foreground application window. */
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        val ws = try { windows } catch (_: Exception) { null }.orEmpty()
        if (ws.isNotEmpty()) {
            val apps = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val win = apps.firstOrNull { it.isActive } ?: apps.maxByOrNull { it.layer }
            win?.root?.let { return it }
        }
        return try { rootInActiveWindow } catch (_: Exception) { null }
    }

    // --------------------------------------------------------------- settings

    private var lastLanguage: String? = null

    /** Context in the language chosen in the app's settings. */
    private fun localized(): Context = Locales.wrap(this, settings.value.language)

    private fun localizedSubtitle(appId: String): String? {
        val res = when (appId) {
            "instagram" -> R.string.subtitle_instagram
            "youtube" -> R.string.subtitle_youtube
            "tiktok" -> R.string.subtitle_tiktok
            "facebook" -> R.string.subtitle_facebook
            else -> return null
        }
        return localized().getString(res)
    }

    private fun onSettingsChanged(s: Settings) {
        val ctrl = controller ?: return
        if (lastLanguage != null && lastLanguage != s.language) {
            // Language changed: the notification must be rewritten.
            statusNotification.cancel()
        }
        lastLanguage = s.language
        val app = ctrl.currentAppId
        if (!s.masterEnabled || (app != null && !s.isAppEnabled(app))) {
            guarded("disable") { ctrl.onDisabled() }
        }
        statusNotification.update(statusNotificationWanted())
    }

    private fun onCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.ExportTree -> exportTree(cmd.delaySeconds)
            ServiceCommand.RefreshNotification -> statusNotification.update(statusNotificationWanted())
        }
    }

    /** The status notification is shown while the service runs and blocking is on. */
    private fun statusNotificationWanted(): Boolean = controller != null && settings.value.masterEnabled

    // --------------------------------------------------------------- receiver

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> guarded("screen off") {
                    pendingEval?.cancel()
                    pendingEval = null
                    controller?.onScreenOff()
                }
                StatusNotification.ACTION_DISMISSED -> guarded("notification") {
                    statusNotification.onDismissed(statusNotificationWanted())
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(StatusNotification.ACTION_DISMISSED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    // ------------------------------------------------------------ diagnostics

    /** Exports the tree of the foreground window after [delaySeconds] seconds. */
    private fun exportTree(delaySeconds: Int) {
        log.add(LogType.EVENT, "Exporting the tree in $delaySeconds s: open the screen to analyse")
        scope.launch {
            delay(delaySeconds * 1000L)
            guarded("tree export") {
                val root = foregroundRoot()
                if (root == null) {
                    log.error("Export: no foreground window")
                    return@guarded
                }
                val (file, count) = TreeDumper.dump(this@ReelsBlockerService, root)
                log.add(LogType.EVENT, "Tree exported: ${file.name} ($count nodes)")
            }
        }
    }

    private fun loadImePackages(): Set<String> = try {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** No exception may escape the service: it would crash the app. */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logError(what, e)
        }
    }

    private fun logError(what: String, e: Throwable) {
        if (::log.isInitialized) log.error(what, e)
    }

    // ---------------------------------------------------------------- actions

    private inner class Actions : BlockActions {
        override fun showOverlay(model: OverlayModel) = overlay.show(model)
        override fun hideOverlay() = overlay.hide()
        override fun muteAudio() = log.add(LogType.EVENT, ducker.mute())
        override fun restoreAudio() = log.add(LogType.EVENT, ducker.restore())
        override fun performExit(action: ExitAction) {
            performGlobalAction(if (action == ExitAction.HOME) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        }
        override fun evaluateNow(): Detection {
            val d = evaluate()
            publishDetection(d)
            return d
        }
    }

    companion object {
        private const val THROTTLE_MS = 250L
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
