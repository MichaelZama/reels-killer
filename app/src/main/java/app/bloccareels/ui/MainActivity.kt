package app.bloccareels.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bloccareels.AppContainer
import app.bloccareels.ServiceCommand
import app.bloccareels.container
import app.bloccareels.data.Locales
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val c = applicationContext.container
        setContent {
            ReelsKillerTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(AppColors.Background)
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

    // If the language was changed from the system settings (Android 13+), save it
    // so that the service and the notification use it too.
    LaunchedEffect(Unit) {
        val ui = Locales.currentUiTag()
        if (ui != settings.language && ui in Locales.SUPPORTED) c.settings.setLanguage(ui)
    }

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

    // Permission for the "is on" notification (Android 13+): asked only once.
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
