package app.bloccareels.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Content of `assets/rules.json`: how to recognise short videos in each host app. */
@Serializable
data class RulesFile(
    val version: Int = 0,
    val apps: List<AppRules> = emptyList(),
) {
    companion object {
        val EMPTY = RulesFile()
    }
}

/** Rules of one host app. [rules] are tried in order and the first match wins. */
@Serializable
data class AppRules(
    val id: String,
    val packages: List<String>,
    /** Block screen subtitle, used only if the app has no translated subtitle. */
    val label: String = "",
    val exitAction: ExitAction = ExitAction.BACK,
    val rules: List<Rule> = emptyList(),
)

/** A rule matches when every [allOf] condition and at least one [anyOf] condition (if any) match. */
@Serializable
data class Rule(
    val name: String,
    val mode: RuleMode = RuleMode.FEED,
    /** Matches the whole app, without looking at the screen. */
    val always: Boolean = false,
    val allOf: List<Condition> = emptyList(),
    val anyOf: List<Condition> = emptyList(),
    /** With [RuleMode.SINGLE]: the scrolling container whose swipe means "next video". */
    val pagerViewId: String? = null,
)

/** How a matching rule is treated. */
@Serializable
enum class RuleMode {
    /** The short-video section: block right away. */
    @SerialName("feed")
    FEED,

    /** A single video, for example opened from a chat: block only at the first swipe. */
    @SerialName("single")
    SINGLE,
}

/** What "Exit" does in the host app. */
@Serializable
enum class ExitAction {
    @SerialName("back")
    BACK,

    @SerialName("home")
    HOME,
}

/** A condition on a single node; it matches if one visible node satisfies every field that is set. */
@Serializable
data class Condition(
    val viewId: String? = null,
    val descAnyOf: List<String>? = null,
    val textAnyOf: List<String>? = null,
    val selected: Boolean? = null,
    val scrollable: Boolean? = null,
    /** Minimum node height as a fraction of the screen height. */
    val minHeightFraction: Double? = null,
)

object RulesLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the rules file. On error it returns an empty set and calls [onError]:
     * the app keeps running, it just blocks nothing.
     * An unknown value in "mode" or "exitAction" makes the whole file invalid;
     * the file bundled with the app is checked by the tests.
     */
    fun parse(text: String, onError: (String) -> Unit = {}): RulesFile =
        try {
            json.decodeFromString(RulesFile.serializer(), text)
        } catch (e: Exception) {
            onError("Invalid rules file: ${e.message?.take(200)}")
            RulesFile.EMPTY
        }
}
