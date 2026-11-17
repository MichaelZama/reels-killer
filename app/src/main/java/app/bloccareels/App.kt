package app.bloccareels

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import app.bloccareels.data.Locales
import app.bloccareels.data.MediaImporter
import app.bloccareels.data.SettingsRepository
import app.bloccareels.detect.DetectorEngine
import app.bloccareels.detect.RulesFile
import app.bloccareels.detect.RulesLoader
import app.bloccareels.diag.DiagnosticsLog
import app.bloccareels.diag.LogType
import app.bloccareels.overlay.AudioDucker
import app.bloccareels.overlay.SystemMusicStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        container = AppContainer(this)
        reportPreviousCrash()
        recoverMutedAudio()
    }

    /** If the previous process died while we had muted the audio, unmute it (see [AudioDucker]). */
    private fun recoverMutedAudio() {
        try {
            val ducker = AudioDucker(
                SystemMusicStream(getSystemService(AudioManager::class.java)),
                AudioDucker.markerFile(this),
            )
            ducker.recoverAfterProcessDeath()?.let { container.diagnostics.add(LogType.EVENT, it) }
        } catch (e: Exception) {
            container.diagnostics.error("Audio recovery at startup", e)
        }
    }

    /**
     * Saves the last crash to a file (only the stack trace, no screen content),
     * so that it shows up in the diagnostics log on the next start.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(filesDir, CRASH_FILE).writeText(
                    "${LocalDateTime.now()} thread=${thread.name}\n$trace",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportPreviousCrash() {
        try {
            val f = File(filesDir, CRASH_FILE)
            if (!f.exists()) return
            val lines = f.readLines()
            f.delete()
            // The first lines are enough to see where it happened.
            container.diagnostics.error("Previous crash:\n" + lines.take(CRASH_LINES_SHOWN).joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
        private const val CRASH_LINES_SHOWN = 18
    }
}

/** Objects shared by the service and the activity, which live in the same process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnostics = DiagnosticsLog()

    /** Requests from the UI to the service (same process). */
    val serviceCommands = MutableSharedFlow<ServiceCommand>(extraBufferCapacity = 8)

    val settings = SettingsRepository(
        context = context,
        defaultMessages = { tag ->
            Locales.wrap(context, tag).resources.getStringArray(R.array.default_messages).toList()
        },
        scope = appScope,
    )

    val rules: RulesFile = try {
        val text = context.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
        RulesLoader.parse(text) { diagnostics.error(it) }
    } catch (e: Exception) {
        diagnostics.error("Cannot read $RULES_ASSET", e)
        RulesFile.EMPTY
    }

    val engine = DetectorEngine(rules)

    private val appContext = context.applicationContext

    /** Deletes the media files that neither a level nor the general media use any more. */
    suspend fun cleanupMedia() {
        val s = settings.settingsFlow.first()
        val keep = (s.levelMedia.mapNotNull { it?.path } + listOfNotNull(s.customMedia?.path)).toSet()
        MediaImporter.cleanup(appContext, keep)
    }

    private val packageManager = context.packageManager

    /** True if at least one package of the host app is installed. */
    fun isAppInstalled(appId: String): Boolean {
        val app = engine.appById(appId) ?: return false
        return app.packages.any { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    companion object {
        const val RULES_ASSET = "rules.json"
    }
}

sealed interface ServiceCommand {
    data class ExportTree(val delaySeconds: Int) : ServiceCommand
    data object RefreshNotification : ServiceCommand
}

val Context.container: AppContainer get() = (applicationContext as App).container
