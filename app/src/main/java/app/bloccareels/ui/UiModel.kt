package app.bloccareels.ui

import app.bloccareels.AppContainer
import app.bloccareels.ServiceCommand
import app.bloccareels.data.CustomMedia
import app.bloccareels.data.Illustration
import app.bloccareels.data.Settings
import app.bloccareels.data.SettingsRepository
import app.bloccareels.diag.DiagnosticsLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Everything the screens can read or change. They depend on this class instead of the whole
 * [AppContainer]. Writes run on the application scope, so they complete even if the screen
 * that started them is closed.
 */
class UiModel(private val container: AppContainer) {
    val settings: StateFlow<Settings> get() = container.settings.state

    /** True once the settings have been read from disk. */
    val settingsLoaded: StateFlow<Boolean> get() = container.settings.loaded

    val diagnostics: DiagnosticsLog get() = container.diagnostics

    val rulesVersion: Int get() = container.rules.version

    fun isAppInstalled(appId: String): Boolean = container.isAppInstalled(appId)

    // --------------------------------------------------------------- settings

    fun setMasterEnabled(v: Boolean) = save { it.setMasterEnabled(v) }
    fun setOnboardingDone() = save { it.setOnboardingDone(true) }
    fun setAppEnabled(id: String, v: Boolean) = save { it.setAppEnabled(id, v) }
    fun setTruceSeconds(v: Int) = save { it.setTruceSeconds(v) }
    fun setContinueDelaySeconds(v: Int) = save { it.setContinueDelaySeconds(v) }
    fun setEscalatingDelay(v: Boolean) = save { it.setEscalatingDelay(v) }
    fun setMuteAudio(v: Boolean) = save { it.setMuteAudio(v) }
    fun setIllustration(v: Illustration) = save { it.setIllustration(v) }
    fun setLanguage(tag: String) = save { it.setLanguage(tag) }

    // ------------------------------------------------- custom media and levels

    /** Sets and selects the general custom media; returns once it is saved. */
    suspend fun setCustomMedia(media: CustomMedia) = container.settings.setCustomMedia(media)

    /** Removes the general custom media and deletes the files nothing uses any more. */
    fun removeCustomMedia() = inAppScope {
        container.settings.clearCustomMedia()
        container.cleanupMedia()
    }

    /** Saves the levels and deletes the media files nothing uses any more. */
    fun saveLevels(messages: List<String>, media: List<CustomMedia?>) = inAppScope {
        container.settings.setLevels(messages, media)
        container.cleanupMedia()
    }

    /** Goes back to the default messages, without media. */
    fun resetLevels() = inAppScope {
        container.settings.resetMessages()
        container.cleanupMedia()
    }

    // ---------------------------------------------------------------- service

    fun exportTree(delaySeconds: Int) {
        container.serviceCommands.tryEmit(ServiceCommand.ExportTree(delaySeconds))
    }

    fun refreshNotification() {
        container.serviceCommands.tryEmit(ServiceCommand.RefreshNotification)
    }

    private fun save(block: suspend (SettingsRepository) -> Unit) = inAppScope { block(container.settings) }

    private fun inAppScope(block: suspend () -> Unit) {
        container.appScope.launch { block() }
    }
}
