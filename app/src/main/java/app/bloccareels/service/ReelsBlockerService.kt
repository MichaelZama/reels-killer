package app.bloccareels.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.ServiceCommand
import app.bloccareels.block.AppMeta
import app.bloccareels.block.BlockActions
import app.bloccareels.block.BlockController
import app.bloccareels.block.Counters
import app.bloccareels.block.OverlayModel
import app.bloccareels.container
import app.bloccareels.data.Locales
import app.bloccareels.data.Settings
import app.bloccareels.data.SettingsRepository
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction
import app.bloccareels.detect.describe
import app.bloccareels.diag.DiagnosticsLog
import app.bloccareels.diag.LogType
import app.bloccareels.diag.TreeDumper
import app.bloccareels.overlay.AudioDucker
import app.bloccareels.overlay.OverlayManager
import app.bloccareels.overlay.SystemMusicStream
import app.bloccareels.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
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

    private var lastEvalAt = 0L
    private var pendingEval: Job? = null
    private var lastForegroundPackage: String? = null
    private var lastDetection: String? = null
    private var imePackages: Set<String> = emptySet()
    private var statusNotificationShown = false
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
        cancelStatusNotification()
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
            cancelStatusNotification()
        }
        lastLanguage = s.language
        val app = ctrl.currentAppId
        if (!s.masterEnabled || (app != null && !s.isAppEnabled(app))) {
            guarded("disable") { ctrl.onDisabled() }
        }
        updateStatusNotification()
    }

    private fun onCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.ExportTree -> exportTree(cmd.delaySeconds)
            ServiceCommand.RefreshNotification -> updateStatusNotification()
        }
    }

    // --------------------------------------------------------------- receiver

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> guarded("screen off") {
                    pendingEval?.cancel()
                    pendingEval = null
                    controller?.onScreenOff()
                }
                // From Android 14 the user can swipe away ongoing notifications too: we put it back.
                ACTION_NOTIFICATION_DISMISSED -> guarded("notification") {
                    statusNotificationShown = false
                    updateStatusNotification()
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_NOTIFICATION_DISMISSED)
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

    // ---------------------------------------------------- status notification

    /** Quiet, ongoing notification: it exists only while the service runs and blocking is on. */
    private fun updateStatusNotification() {
        val shouldShow = controller != null && settings.value.masterEnabled
        if (shouldShow) showStatusNotification() else cancelStatusNotification()
    }

    private fun showStatusNotification() {
        if (statusNotificationShown) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        val text = localized()
        // Channel of the old debug notification, no longer used.
        guarded("debug channel") {
            nm.cancel(OLD_NOTIFICATION_DEBUG)
            nm.deleteNotificationChannel(OLD_CHANNEL_DEBUG)
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, text.getString(R.string.status_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                description = text.getString(R.string.status_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissed = PendingIntent.getBroadcast(
            this, 3,
            Intent(ACTION_NOTIFICATION_DISMISSED).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.getString(R.string.status_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .build()
        nm.notify(NOTIFICATION_STATUS, n)
        statusNotificationShown = true
    }

    private fun cancelStatusNotification() {
        guarded("notification") {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_STATUS)
            nm.cancel(OLD_NOTIFICATION_DEBUG)
        }
        statusNotificationShown = false
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
            if (::log.isInitialized) log.error(what, e)
        }
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

        /** Channel id kept from older versions ("stato" = status), so no duplicate channel appears. */
        private const val CHANNEL_STATUS = "stato"
        private const val NOTIFICATION_STATUS = 2
        private const val OLD_CHANNEL_DEBUG = "debug"
        private const val OLD_NOTIFICATION_DEBUG = 1
        private const val ACTION_NOTIFICATION_DISMISSED = "app.bloccareels.action.NOTIFICATION_DISMISSED"
    }
}

/** Counters saved in the repository; the returned value is available to the block screen at once. */
private class RepoCounters(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
) : Counters {
    private var date: String? = null
    private var blocks = 0

    override fun onBlockShown(): Int {
        val today = repo.todayString()
        val s = repo.state.value
        val persisted = if (s.counterDate == today) s.blocksToday else 0
        if (date != today) {
            date = today
            blocks = 0
        }
        blocks = maxOf(blocks, persisted) + 1
        scope.launch { repo.increment(SettingsRepository.Counter.BLOCKS) }
        return blocks
    }

    override fun onContinue() {
        scope.launch { repo.increment(SettingsRepository.Counter.CONTINUE) }
    }

    override fun onExit() {
        scope.launch { repo.increment(SettingsRepository.Counter.EXIT) }
    }
}
