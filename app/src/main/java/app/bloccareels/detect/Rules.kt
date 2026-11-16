package app.bloccareels.detect

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
    val exitAction: String = "back",
    val rules: List<Rule> = emptyList(),
)

@Serializable
data class Rule(
    val name: String,
    val mode: String = MODE_FEED,
    val always: Boolean = false,
    val allOf: List<Condition> = emptyList(),
    val anyOf: List<Condition> = emptyList(),
    val pagerViewId: String? = null,
) {
    companion object {
        const val MODE_FEED = "feed"
        const val MODE_SINGLE = "single"
    }
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
     */
    fun parse(text: String, onError: (String) -> Unit = {}): RulesFile =
        try {
            val parsed = json.decodeFromString(RulesFile.serializer(), text)
            val invalid = parsed.apps.flatMap { app ->
                app.rules.filter { it.mode != Rule.MODE_FEED && it.mode != Rule.MODE_SINGLE }
                    .map { "${app.id}/${it.name}" }
            }
            if (invalid.isNotEmpty()) onError("Regole con mode sconosciuto: ${invalid.joinToString()}")
            parsed
        } catch (e: Exception) {
            onError("File delle regole non valido: ${e.message?.take(200)}")
            RulesFile.EMPTY
        }
}
