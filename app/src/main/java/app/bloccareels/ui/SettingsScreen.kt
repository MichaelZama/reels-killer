package app.bloccareels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.data.AppIds
import app.bloccareels.data.Illustration
import app.bloccareels.data.Locales
import app.bloccareels.data.Settings
import app.bloccareels.overlay.OverlayBinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The single, scrolling settings screen. */
@Composable
fun SettingsScreen(
    c: AppContainer,
    settings: Settings,
    serviceOn: Boolean,
    batteryOk: Boolean,
    onOpenMessages: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val repo = c.settings
    fun save(block: suspend () -> Unit) {
        c.appScope.launch { block() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            color = AppColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(start = 4.dp, top = 8.dp)
                .semantics { heading() },
        )

        // ------------------------------------------------------------ Status
        SectionCard(stringResource(R.string.section_status)) {
            SwitchRow(
                title = stringResource(R.string.master_switch),
                note = stringResource(R.string.interruptions_today, settings.blocksToday),
                checked = settings.masterEnabled,
                onCheckedChange = { v -> save { repo.setMasterEnabled(v) } },
            )
            val diag by c.diagnostics.status.collectAsStateWithLifecycle()
            // The service can connect a moment after the app opens: allow a short grace period.
            var graceOver by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SERVICE_CONNECT_GRACE_MS)
                graceOver = true
            }
            when {
                !serviceOn -> StatusRow(
                    kind = StatusKind.WARN,
                    text = stringResource(R.string.service_off),
                    onFix = { SystemStatus.openAccessibilitySettings(context) },
                )
                !diag.serviceConnected && graceOver -> {
                    StatusRow(
                        kind = StatusKind.WARN,
                        text = stringResource(R.string.service_stopped),
                        note = stringResource(R.string.service_stopped_note),
                        onFix = { SystemStatus.openAccessibilitySettings(context) },
                    )
                    if (SystemStatus.isXiaomi()) {
                        Text(stringResource(R.string.xiaomi_autostart_note), color = AppColors.TextSecondary, fontSize = 13.sp)
                        Button(
                            onClick = { SystemStatus.openAutostartSettings(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Background,
                                contentColor = AppColors.TextPrimary,
                            ),
                        ) { Text(stringResource(R.string.xiaomi_autostart_button)) }
                    }
                }
                !settings.masterEnabled -> StatusRow(
                    kind = StatusKind.PAUSE,
                    text = stringResource(R.string.service_paused),
                )
                else -> StatusRow(
                    kind = StatusKind.OK,
                    text = stringResource(R.string.service_on),
                )
            }
            if (!batteryOk) {
                StatusRow(
                    kind = StatusKind.WARN,
                    text = stringResource(R.string.battery_warning),
                    onFix = { SystemStatus.requestIgnoreBatteryOptimizations(context) },
                )
            }
        }

        // ---------------------------------------------------- Monitored apps
        SectionCard(stringResource(R.string.section_apps)) {
            val installed = remember { AppIds.ALL.associateWith { c.isAppInstalled(it) } }
            AppIds.ALL.forEach { id ->
                val isInstalled = installed[id] == true
                val note = when {
                    !isInstalled -> stringResource(R.string.app_not_installed)
                    id == AppIds.FACEBOOK -> stringResource(R.string.app_experimental)
                    else -> null
                }
                SwitchRow(
                    title = stringResource(appLabel(id)),
                    note = note,
                    checked = isInstalled && settings.isAppEnabled(id),
                    enabled = isInstalled,
                    onCheckedChange = { v -> save { repo.setAppEnabled(id, v) } },
                )
            }
        }

        // --------------------------------------------------------- Behaviour
        SectionCard(stringResource(R.string.section_behavior)) {
            Text(stringResource(R.string.truce_label), color = AppColors.TextPrimary, fontSize = 16.sp)
            ChoiceRow(
                options = Settings.TRUCE_CHOICES,
                selected = settings.truceSeconds,
                label = { stringResource(R.string.seconds_short, it) },
                description = { stringResource(R.string.seconds_choice_description, it) },
                onSelect = { v -> save { repo.setTruceSeconds(v) } },
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.delay_label), color = AppColors.TextPrimary, fontSize = 16.sp)
            ChoiceRow(
                options = Settings.DELAY_CHOICES,
                selected = settings.continueDelaySeconds,
                label = { stringResource(R.string.seconds_short, it) },
                description = { stringResource(R.string.seconds_choice_description, it) },
                onSelect = { v -> save { repo.setContinueDelaySeconds(v) } },
            )
            SwitchRow(
                title = stringResource(R.string.escalating_label),
                note = stringResource(R.string.escalating_note),
                checked = settings.escalatingDelay,
                onCheckedChange = { v -> save { repo.setEscalatingDelay(v) } },
            )
            SwitchRow(
                title = stringResource(R.string.mute_label),
                checked = settings.muteAudio,
                onCheckedChange = { v -> save { repo.setMuteAudio(v) } },
            )
        }

        // ------------------------------------------------------ Block screen
        SectionCard(stringResource(R.string.section_block_screen)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onOpenMessages),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.messages_label),
                    color = AppColors.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.messages_count, settings.messages.size), color = AppColors.TextSecondary, fontSize = 14.sp)
                Text("  ›", color = AppColors.TextSecondary, fontSize = 18.sp)
            }
            Text(stringResource(R.string.illustration_label), color = AppColors.TextPrimary, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Illustration.entries.filter { it != Illustration.CUSTOM }.forEach { ill ->
                    IllustrationThumb(
                        illustration = ill,
                        selected = settings.illustration == ill,
                        onSelect = { save { repo.setIllustration(ill) } },
                    )
                }
                CustomMediaThumb(c = c, settings = settings)
            }
            CustomMediaActions(c = c, settings = settings)
            Button(
                onClick = onPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Background, contentColor = AppColors.TextPrimary),
            ) { Text(stringResource(R.string.preview_button), fontSize = 15.sp) }
        }

        // ------------------------------------------------------------- Language
        SectionCard(stringResource(R.string.section_language)) {
            ChoiceRow(
                options = Locales.SUPPORTED,
                selected = settings.language,
                label = { languageLabel(it) },
                description = { languageLabel(it) },
                onSelect = { tag ->
                    Locales.applyToUi(tag)
                    save { repo.setLanguage(tag) }
                },
            )
        }

        // --------------------------------------------------------------- Update
        UpdateSection(c = c)

        // ---------------------------------------------------------- Diagnostics
        DiagnosticsSection(c = c, settings = settings)

        Spacer(Modifier.height(24.dp))
    }
}

internal val THUMB_SIZE = 64.dp

private const val SERVICE_CONNECT_GRACE_MS = 2000L

@Composable
private fun languageLabel(tag: String): String = stringResource(
    when (tag) {
        "it" -> R.string.language_italian
        "en" -> R.string.language_english
        else -> R.string.language_system
    },
)

fun appLabel(id: String): Int = when (id) {
    AppIds.INSTAGRAM -> R.string.app_instagram
    AppIds.YOUTUBE -> R.string.app_youtube
    AppIds.TIKTOK -> R.string.app_tiktok
    AppIds.FACEBOOK -> R.string.app_facebook
    else -> error("Unknown app id: $id")
}

private enum class StatusKind { OK, WARN, PAUSE }

@Composable
private fun StatusRow(kind: StatusKind, text: String, note: String? = null, onFix: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(
                when (kind) {
                    StatusKind.OK -> R.drawable.ic_check
                    StatusKind.WARN -> R.drawable.ic_warning
                    StatusKind.PAUSE -> R.drawable.ic_pause
                },
            ),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = AppColors.TextPrimary, fontSize = 15.sp)
            if (note != null) Text(note, color = AppColors.TextSecondary, fontSize = 13.sp)
        }
        if (onFix != null) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onFix,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Warning, contentColor = AppColors.OnAccent),
            ) { Text(stringResource(R.string.fix), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun IllustrationThumb(illustration: Illustration, selected: Boolean, onSelect: () -> Unit) {
    val name = stringResource(
        when (illustration) {
            Illustration.SUNSET -> R.string.ill_sunset
            Illustration.CUP -> R.string.ill_cup
            Illustration.LEAF -> R.string.ill_leaf
            Illustration.CUSTOM -> R.string.ill_custom
        },
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(THUMB_SIZE)
                .clip(CircleShape)
                .background(AppColors.Background)
                .border(if (selected) 3.dp else 1.dp, if (selected) AppColors.Accent else AppColors.Border, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(OverlayBinder.drawableFor(illustration)),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary, fontSize = 13.sp)
    }
}
