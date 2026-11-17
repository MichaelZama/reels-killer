package app.bloccareels.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

@Serializable
private data class StoredMedia(val path: String, val kind: String)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Values used when a key is missing or invalid: the defaults of [Settings]. */
private val DEFAULTS = Settings()

/** The only way to DataStore. */
class SettingsRepository(
    context: Context,
    /** Initial messages in the given language ("" = system). */
    private val defaultMessages: (language: String) -> List<String>,
    scope: CoroutineScope,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val store = context.applicationContext.settingsStore
    private val messagesSerializer = ListSerializer(String.serializer())
    private val levelMediaSerializer = ListSerializer(StoredMedia.serializer().nullable)

    val settingsFlow: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::toSettings)

    private val _loaded = MutableStateFlow(false)

    /** True after the first real read from disk. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Last known value, for whoever has to read synchronously (service and controller). */
    val state: StateFlow<Settings> = settingsFlow
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, Settings(messages = defaultMessages("")))

    fun todayString(): String = today()

    private fun toSettings(p: Preferences): Settings {
        val isToday = p[K.counterDate] == today()
        val media = p[K.mediaPath]?.let { path ->
            MediaKind.fromKey(p[K.mediaKind])?.let { kind -> CustomMedia(path, kind) }
        }
        val storedMessages = p[K.messagesJson]
            ?.let { runCatching { Json.decodeFromString(messagesSerializer, it) }.getOrNull() }
            ?.takeIf { list -> list.any { it.isNotBlank() } }
        val language = p[K.language].orEmpty().takeIf { it in Locales.SUPPORTED }.orEmpty()
        val messages = storedMessages ?: defaultMessages(language)
        val levelMedia: List<CustomMedia?> = if (storedMessages == null) {
            emptyList()
        } else {
            p[K.levelMediaJson]
                ?.let { runCatching { Json.decodeFromString(levelMediaSerializer, it) }.getOrNull() }
                ?.map { stored -> stored?.let { m -> MediaKind.fromKey(m.kind)?.let { CustomMedia(m.path, it) } } }
                .orEmpty()
                .let { list -> List(messages.size) { i -> list.getOrNull(i) } }
        }
        return Settings(
            masterEnabled = p[K.master] ?: DEFAULTS.masterEnabled,
            onboardingDone = p[K.onboarding] ?: DEFAULTS.onboardingDone,
            appEnabled = AppIds.ALL.associateWith { id -> p[K.app(id)] ?: AppIds.DEFAULT_ENABLED.getValue(id) },
            truceSeconds = p[K.truce]?.takeIf { it in Settings.TRUCE_CHOICES } ?: DEFAULTS.truceSeconds,
            continueDelaySeconds = p[K.delay]?.takeIf { it in Settings.DELAY_CHOICES } ?: DEFAULTS.continueDelaySeconds,
            escalatingDelay = p[K.escalating] ?: DEFAULTS.escalatingDelay,
            muteAudio = p[K.mute] ?: DEFAULTS.muteAudio,
            illustration = Illustration.fromKey(p[K.illustration]).let {
                if (it == Illustration.CUSTOM && media == null) DEFAULTS.illustration else it
            },
            customMedia = media,
            messages = messages,
            levelMedia = levelMedia,
            language = language,
            counterDate = p[K.counterDate].orEmpty(),
            blocksToday = if (isToday) p[K.blocks] ?: 0 else 0,
            continueToday = if (isToday) p[K.continues] ?: 0 else 0,
            exitToday = if (isToday) p[K.exits] ?: 0 else 0,
        )
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun setMasterEnabled(v: Boolean) = edit { it[K.master] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[K.onboarding] = v }
    suspend fun setAppEnabled(id: String, v: Boolean) = edit { it[K.app(id)] = v }
    suspend fun setTruceSeconds(v: Int) = edit { it[K.truce] = v }
    suspend fun setContinueDelaySeconds(v: Int) = edit { it[K.delay] = v }
    suspend fun setEscalatingDelay(v: Boolean) = edit { it[K.escalating] = v }
    suspend fun setMuteAudio(v: Boolean) = edit { it[K.mute] = v }
    suspend fun setIllustration(v: Illustration) = edit { it[K.illustration] = v.key }
    /** Sets the custom media and selects it. */
    suspend fun setCustomMedia(media: CustomMedia) = edit {
        it[K.mediaPath] = media.path
        it[K.mediaKind] = media.kind.key
        it[K.illustration] = Illustration.CUSTOM.key
    }

    suspend fun clearCustomMedia() = edit {
        it.remove(K.mediaPath)
        it.remove(K.mediaKind)
        if (it[K.illustration] == Illustration.CUSTOM.key) it[K.illustration] = DEFAULTS.illustration.key
    }

    suspend fun setLanguage(tag: String) = edit { it[K.language] = tag }

    /**
     * Saves the levels: message and media of each one. Levels with an empty message are
     * dropped together with their media. At least one must remain.
     */
    suspend fun setLevels(messages: List<String>, media: List<CustomMedia?>) {
        val pairs = messages.mapIndexed { i, m -> m.trim() to media.getOrNull(i) }.filter { it.first.isNotEmpty() }
        if (pairs.isEmpty()) return
        edit {
            it[K.messagesJson] = Json.encodeToString(messagesSerializer, pairs.map { p -> p.first })
            it[K.levelMediaJson] = Json.encodeToString(
                levelMediaSerializer,
                pairs.map { p -> p.second?.let { m -> StoredMedia(m.path, m.kind.key) } },
            )
        }
    }

    suspend fun resetMessages() = edit {
        it.remove(K.messagesJson)
        it.remove(K.levelMediaJson)
    }

    enum class Counter { BLOCKS, CONTINUE, EXIT }

    /** Increments one of today's counters; if the date changed, first resets all three. */
    suspend fun increment(counter: Counter) = edit { p ->
        val t = today()
        if (p[K.counterDate] != t) {
            p[K.counterDate] = t
            p[K.blocks] = 0
            p[K.continues] = 0
            p[K.exits] = 0
        }
        val key = when (counter) {
            Counter.BLOCKS -> K.blocks
            Counter.CONTINUE -> K.continues
            Counter.EXIT -> K.exits
        }
        p[key] = (p[key] ?: 0) + 1
    }

    private object K {
        val master = booleanPreferencesKey("master_enabled")
        val onboarding = booleanPreferencesKey("onboarding_done")
        fun app(id: String) = booleanPreferencesKey("app_$id")
        val truce = intPreferencesKey("truce_seconds")
        val delay = intPreferencesKey("continue_delay_seconds")
        val escalating = booleanPreferencesKey("escalating_delay")
        val mute = booleanPreferencesKey("mute_audio")
        val illustration = stringPreferencesKey("illustration")
        val mediaPath = stringPreferencesKey("custom_media_path")
        val mediaKind = stringPreferencesKey("custom_media_kind")
        val levelMediaJson = stringPreferencesKey("level_media_json")
        val language = stringPreferencesKey("language")
        val messagesJson = stringPreferencesKey("messages_json")
        val counterDate = stringPreferencesKey("counter_date")
        val blocks = intPreferencesKey("counter_blocks")
        val continues = intPreferencesKey("counter_continue")
        val exits = intPreferencesKey("counter_exit")
    }
}
