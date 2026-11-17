package app.bloccareels.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogType { EVENTO, RILEVAMENTO, TRANSIZIONE, ERRORE }

data class LogEntry(val timeMs: Long, val type: LogType, val text: String)

data class DiagStatus(
    val lastPackage: String? = null,
    val lastEventAtMs: Long? = null,
    val controllerState: String = "INATTIVO",
    val lastDetection: String = "Nessuno",
    val serviceConnected: Boolean = false,
)

/** Buffer circolare in memoria. Kotlin puro. Non salva mai il contenuto delle schermate. */
class DiagnosticsLog(
    private val capacity: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** La voce più recente è la prima. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _status = MutableStateFlow(DiagStatus())
    val status: StateFlow<DiagStatus> = _status.asStateFlow()

    fun add(type: LogType, text: String) {
        val entry = LogEntry(clock(), type, text)
        _entries.update { (listOf(entry) + it).take(capacity) }
    }

    fun error(text: String, t: Throwable? = null) =
        add(LogType.ERRORE, if (t == null) text else "$text: ${t.javaClass.simpleName} ${t.message.orEmpty()}".trim())

    fun updateStatus(transform: (DiagStatus) -> DiagStatus) = _status.update(transform)

    fun clear() {
        _entries.value = emptyList()
    }
}
