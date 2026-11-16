package app.bloccareels.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import app.bloccareels.ServiceCommand
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bloccareels.AppContainer
import app.bloccareels.container
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val c = applicationContext.container
        setContent {
            BloccaTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BrColors.Sfondo)
                        .safeDrawingPadding(),
                ) {
                    AppRoot(c)
                }
            }
        }
    }
}

private enum class Screen { SETTINGS, MESSAGES, PREVIEW }

@Composable
private fun AppRoot(c: AppContainer) {
    val context = LocalContext.current
    val loaded by c.settings.loaded.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()

    var serviceOn by remember { mutableStateOf(SystemStatus.isServiceEnabled(context)) }
    var batteryOk by remember { mutableStateOf(SystemStatus.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = SystemStatus.isServiceEnabled(context)
        batteryOk = SystemStatus.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    if (!loaded) return

    var screenName by rememberSaveable { mutableStateOf(Screen.SETTINGS.name) }
    val screen = Screen.valueOf(screenName)

    if (!settings.onboardingDone) {
        OnboardingScreen(
            serviceOn = serviceOn,
            batteryOk = batteryOk,
            onFinish = { c.appScope.launch { c.settings.setOnboardingDone(true) } },
        )
        return
    }

    BackHandler(enabled = screen != Screen.SETTINGS) { screenName = Screen.SETTINGS.name }

    // Permesso per la notifica "attivo" (Android 13+): chiesto una sola volta.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        c.serviceCommands.tryEmit(ServiceCommand.RefreshNotification)
    }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!notifAsked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            c = c,
            settings = settings,
            serviceOn = serviceOn,
            batteryOk = batteryOk,
            onOpenMessages = { screenName = Screen.MESSAGES.name },
            onPreview = { screenName = Screen.PREVIEW.name },
        )
        Screen.MESSAGES -> MessagesScreen(
            c = c,
            initial = settings,
            onBack = { screenName = Screen.SETTINGS.name },
        )
        Screen.PREVIEW -> PreviewScreen(
            settings = settings,
            onClose = { screenName = Screen.SETTINGS.name },
        )
    }
}
