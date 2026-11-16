package app.bloccareels

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import app.bloccareels.data.SettingsRepository
import app.bloccareels.detect.DetectorEngine
import app.bloccareels.detect.RulesFile
import app.bloccareels.detect.RulesLoader
import app.bloccareels.diag.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        container = AppContainer(this)
        reportPreviousCrash()
    }

    /**
     * Salva su file l'ultimo crash (solo lo stack trace, nessun contenuto delle schermate),
     * così al riavvio compare nel log di diagnostica.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.time.LocalDateTime.now()} thread=${thread.name}\n$trace",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportPreviousCrash() {
        try {
            val f = java.io.File(filesDir, CRASH_FILE)
            if (!f.exists()) return
            val lines = f.readLines()
            f.delete()
            // Le prime righe bastano a capire dove è successo.
            container.diagnostics.error("Crash precedente:\n" + lines.take(18).joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
    }
}

/** Oggetti condivisi tra servizio e activity, che vivono nello stesso processo. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnostics = DiagnosticsLog()

    /** Richieste dall'interfaccia al servizio (stesso processo). */
    val serviceCommands = kotlinx.coroutines.flow.MutableSharedFlow<ServiceCommand>(extraBufferCapacity = 8)

    val settings = SettingsRepository(
        context = context,
        defaultMessages = { tag ->
            app.bloccareels.data.Locales.wrap(context, tag).resources.getStringArray(R.array.default_messages).toList()
        },
        scope = appScope,
    )

    val rules: RulesFile = try {
        val text = context.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
        RulesLoader.parse(text) { diagnostics.error(it) }
    } catch (e: Exception) {
        diagnostics.error("Impossibile leggere $RULES_ASSET", e)
        RulesFile.EMPTY
    }

    val engine = DetectorEngine(rules)

    private val appContext = context.applicationContext

    /** Cancella i file media che nessun livello ne' il media generale usano piu'. */
    suspend fun cleanupMedia() {
        val s = settings.settingsFlow.first()
        val keep = (s.levelMedia.mapNotNull { it?.path } + listOfNotNull(s.customMedia?.path)).toSet()
        app.bloccareels.data.MediaImporter.cleanup(appContext, keep)
    }

    private val packageManager = context.packageManager

    /** True se almeno un package dell'app ospite è installato. */
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
