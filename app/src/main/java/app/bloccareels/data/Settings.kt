package app.bloccareels.data

/**
 * Drawing on the block screen. [key] is what gets saved: it keeps the original
 * Italian values so that settings saved by older versions still load.
 */
enum class Illustration(val key: String) {
    SUNSET("tramonto"),
    CUP("tazza"),
    LEAF("foglia"),

    /** Image, GIF or video picked by the user. */
    CUSTOM("personalizzato");

    companion object {
        /** Unknown or missing keys fall back to [SUNSET]. */
        fun fromKey(key: String?): Illustration = entries.firstOrNull { it.key == key } ?: SUNSET
    }
}

/** Kind of custom media. */
enum class MediaKind(val key: String) {
    IMAGE("image"),
    GIF("gif"),
    VIDEO("video");

    companion object {
        fun fromKey(key: String?): MediaKind? = entries.firstOrNull { it.key == key }
    }
}

/** Media picked by the user, copied into the app's private folder. */
data class CustomMedia(val path: String, val kind: MediaKind)

/** Ids of the monitored apps, equal to the "id" field in rules.json. */
object AppIds {
    const val INSTAGRAM = "instagram"
    const val YOUTUBE = "youtube"
    const val TIKTOK = "tiktok"
    const val FACEBOOK = "facebook"
    val ALL = listOf(INSTAGRAM, YOUTUBE, TIKTOK, FACEBOOK)

    val DEFAULT_ENABLED = mapOf(
        INSTAGRAM to true,
        YOUTUBE to true,
        TIKTOK to true,
        FACEBOOK to false,
    )
}

/**
 * User preferences. Plain Kotlin: the controller tests use it too.
 * The counters are already reset to zero if the saved date is not today.
 */
data class Settings(
    val masterEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
    val appEnabled: Map<String, Boolean> = AppIds.DEFAULT_ENABLED,
    val truceSeconds: Int = 3,
    val continueDelaySeconds: Int = 0,
    val escalatingDelay: Boolean = true,
    val muteAudio: Boolean = true,
    val illustration: Illustration = Illustration.SUNSET,
    val customMedia: CustomMedia? = null,
    /** Level messages, in order: level 1 = first block, level 2 = after the first Continue... */
    val messages: List<String> = emptyList(),
    /** Media of each level, aligned with [messages]; null = use the previous level's. */
    val levelMedia: List<CustomMedia?> = emptyList(),
    /** "" = system language, otherwise "it" or "en". */
    val language: String = "",
    val counterDate: String = "",
    val blocksToday: Int = 0,
    val continueToday: Int = 0,
    val exitToday: Int = 0,
) {
    /**
     * The general custom media: only if "custom" is selected. It does not check that the file
     * exists: if it is missing, the block screen shows no image.
     */
    val activeCustomMedia: CustomMedia?
        get() = if (illustration == Illustration.CUSTOM) customMedia else null

    /**
     * Media of level [index] (0 = first): the first media found going back towards level 1,
     * otherwise the general custom media picked under "Drawing".
     */
    fun mediaForLevel(index: Int): CustomMedia? {
        for (i in minOf(index, levelMedia.lastIndex) downTo 0) {
            levelMedia[i]?.let { return it }
        }
        return activeCustomMedia
    }

    fun isAppEnabled(id: String): Boolean = appEnabled[id] ?: AppIds.DEFAULT_ENABLED[id] ?: false

    val enabledAppIds: Set<String>
        get() = if (!masterEnabled) emptySet() else AppIds.ALL.filter { isAppEnabled(it) }.toSet()

    companion object {
        val TRUCE_CHOICES = listOf(3, 5, 10)
        val DELAY_CHOICES = listOf(0, 3, 5, 10)
    }
}
