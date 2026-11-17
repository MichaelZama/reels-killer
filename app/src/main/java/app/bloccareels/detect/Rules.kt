package app.bloccareels.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RulesFile(
    val version: Int = 0,
    val apps: List<AppRules> = emptyList(),
) {
    companion object {
        val EMPTY = RulesFile()
    }
}

@Serializable
data class AppRules(
    val id: String,
    val packages: List<String>,
    val label: String = "",
    val exitAction: ExitAction = ExitAction.BACK,
    val rules: List<Rule> = emptyList(),
)

@Serializable
data class Rule(
    val name: String,
    val mode: RuleMode = RuleMode.FEED,
    val always: Boolean = false,
    val allOf: List<Condition> = emptyList(),
    val anyOf: List<Condition> = emptyList(),
    val pagerViewId: String? = null,
)

/** Come si tratta una regola che corrisponde. */
@Serializable
enum class RuleMode {
    /** Sezione dei video brevi: si blocca subito. */
    @SerialName("feed")
    FEED,

    /** Video singolo, per esempio aperto da una chat: si blocca solo al primo swipe. */
    @SerialName("single")
    SINGLE,
}

/** Cosa fa "Esci" nell'app ospite. */
@Serializable
enum class ExitAction {
    @SerialName("back")
    BACK,

    @SerialName("home")
    HOME,
}

@Serializable
data class Condition(
    val viewId: String? = null,
    val descAnyOf: List<String>? = null,
    val textAnyOf: List<String>? = null,
    val selected: Boolean? = null,
    val scrollable: Boolean? = null,
    val minHeightFraction: Double? = null,
)

object RulesLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Legge il file delle regole. In caso di errore restituisce un insieme vuoto
     * e chiama [onError]: l'app resta in piedi, semplicemente non blocca nulla.
     * Un valore sconosciuto in "mode" o "exitAction" rende non valido tutto il file;
     * il file incluso nell'app è verificato dai test.
     */
    fun parse(text: String, onError: (String) -> Unit = {}): RulesFile =
        try {
            json.decodeFromString(RulesFile.serializer(), text)
        } catch (e: Exception) {
            onError("File delle regole non valido: ${e.message?.take(200)}")
            RulesFile.EMPTY
        }
}
