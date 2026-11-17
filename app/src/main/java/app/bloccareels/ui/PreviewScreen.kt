package app.bloccareels.ui

import android.view.LayoutInflater
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import app.bloccareels.R
import app.bloccareels.block.DelayPolicy
import app.bloccareels.block.OverlayModel
import app.bloccareels.data.Settings
import app.bloccareels.overlay.OverlayBinder

/**
 * Anteprima della schermata di blocco dentro l'app.
 * Usa lo stesso layout dell'overlay; non tocca contatori né audio.
 */
@Composable
fun PreviewScreen(settings: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subtitle = stringResource(R.string.preview_subtitle)

    val model = remember(settings) {
        val messages = settings.messages.ifEmpty { listOf("") }
        val next = 0
        OverlayModel(
            message = messages[next],
            subtitle = subtitle,
            illustration = settings.illustration,
            blocksToday = settings.blocksToday + 1,
            waitSeconds = DelayPolicy.attesa(settings.continueDelaySeconds, settings.escalatingDelay, 0),
            showEscalationHint = false,
            customMedia = settings.mediaForLevel(0),
        )
    }

    val view: View = remember { LayoutInflater.from(context).inflate(R.layout.overlay_block, null) }
    val binder = remember(view) { OverlayBinder(view, scope) }

    DisposableEffect(model) {
        binder.bind(model, onExit = onClose, onContinue = onClose)
        onDispose { binder.cancel() }
    }
    BackHandler(onBack = onClose)

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
