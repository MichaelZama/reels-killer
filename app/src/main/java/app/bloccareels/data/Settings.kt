package app.bloccareels.data

/** Disegno della schermata di blocco. */
enum class Illustration(val key: String) {
    TRAMONTO("tramonto"),
    TAZZA("tazza"),
    FOGLIA("foglia"),

    /** Immagine, GIF o video scelti dall'utente. */
    PERSONALIZZATO("personalizzato");

    companion object {
        fun fromKey(key: String?): Illustration = entries.firstOrNull { it.key == key } ?: TRAMONTO
    }
}

/** Tipo di media personalizzato. */
enum class MediaKind(val key: String) {
    IMAGE("image"),
    GIF("gif"),
    VIDEO("video");

    companion object {
        fun fromKey(key: String?): MediaKind? = entries.firstOrNull { it.key == key }
    }
}

/** Media scelto dall'utente, copiato nella cartella privata dell'app. */
data class CustomMedia(val path: String, val kind: MediaKind)

/** Identificativi delle app monitorate, uguali al campo "id" di rules.json. */
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
 * Preferenze dell'utente (sezione 9). Kotlin puro: lo usa anche il controller nei test.
 * I contatori sono già riportati a zero se la data salvata non è quella di oggi.
 */
data class Settings(
    val masterEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
    val appEnabled: Map<String, Boolean> = AppIds.DEFAULT_ENABLED,
    val truceSeconds: Int = 3,
    val continueDelaySeconds: Int = 0,
    val escalatingDelay: Boolean = true,
    val muteAudio: Boolean = true,
    val illustration: Illustration = Illustration.TRAMONTO,
    val customMedia: CustomMedia? = null,
    /** Messaggi dei livelli, in ordine: livello 1 = primo blocco, livello 2 = dopo il primo Continua... */
    val messages: List<String> = emptyList(),
    /** Media di ogni livello, allineato a [messages]; null = usa quello del livello precedente. */
    val levelMedia: List<CustomMedia?> = emptyList(),
    val lastMessageIndex: Int = -1,
    val debugEnabled: Boolean = false,
    /** "" = lingua del sistema, altrimenti "it" o "en". */
    val language: String = "",
    val counterDate: String = "",
    val blocksToday: Int = 0,
    val continueToday: Int = 0,
    val exitToday: Int = 0,
) {
    /** Media da mostrare: solo se è stato scelto "personalizzato" e il file esiste. */
    val activeCustomMedia: CustomMedia?
        get() = if (illustration == Illustration.PERSONALIZZATO) customMedia else null

    /**
     * Media del livello [index] (0 = primo): il primo media trovato risalendo verso il livello 1,
     * altrimenti il media personalizzato generale scelto in "Disegno".
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
