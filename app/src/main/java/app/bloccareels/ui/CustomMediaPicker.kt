package app.bloccareels.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.data.Illustration
import app.bloccareels.data.MediaImporter
import app.bloccareels.data.MediaKind
import app.bloccareels.data.Settings
import kotlinx.coroutines.launch

/** Apre il selettore di foto e video di sistema e importa il file scelto. */
@Composable
private fun rememberMediaPicker(c: AppContainer, onBusy: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgTooLong = stringResource(R.string.media_too_long, MediaImporter.MAX_VIDEO_SECONDS)
    val msgTooBig = stringResource(R.string.media_too_big)
    val msgUnsupported = stringResource(R.string.media_unsupported)
    val msgFailed = stringResource(R.string.media_failed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onBusy(true)
        scope.launch {
            val msg = when (val r = MediaImporter.import(context, uri)) {
                is MediaImporter.Result.Ok -> {
                    c.settings.setCustomMedia(r.media)
                    null
                }
                MediaImporter.Result.TooLong -> msgTooLong
                MediaImporter.Result.TooBig -> msgTooBig
                MediaImporter.Result.Unsupported -> msgUnsupported
                is MediaImporter.Result.Failed -> {
                    c.diagnostics.error("Importazione media", r.error)
                    msgFailed
                }
            }
            onBusy(false)
            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
}

/** Quarta miniatura: il media dell'utente, oppure "+" per sceglierne uno. */
@Composable
fun CustomMediaThumb(c: AppContainer, settings: Settings) {
    var busy by remember { mutableStateOf(false) }
    val pick = rememberMediaPicker(c) { busy = it }
    val media = settings.customMedia
    val selected = settings.illustration == Illustration.PERSONALIZZATO
    val sizePx = with(LocalDensity.current) { THUMB_SIZE.roundToPx() }
    val thumb: Bitmap? by produceState<Bitmap?>(null, media) {
        value = media?.let { MediaImporter.thumbnail(it, sizePx) }
    }
    val name = stringResource(R.string.ill_custom)
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(THUMB_SIZE)
                .clip(CircleShape)
                .background(BrColors.Sfondo)
                .border(if (selected) 3.dp else 1.dp, if (selected) BrColors.Accento else BrColors.Bordo, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = {
                    if (media == null) pick() else scope.launch { c.settings.setIllustration(Illustration.PERSONALIZZATO) }
                })
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            when {
                busy -> Text("…", color = BrColors.TestoSecondario, fontSize = 20.sp)
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text("+", color = BrColors.Accento, fontSize = 28.sp)
            }
            if (media?.kind == MediaKind.VIDEO && bmp != null && !busy) {
                Text("▶", color = BrColors.Testo, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = if (selected) BrColors.Testo else BrColors.TestoSecondario, fontSize = 13.sp)
    }
}

/** Pulsanti "Cambia" e "Rimuovi" sotto le miniature, solo se c'è un media personalizzato. */
@Composable
fun CustomMediaActions(c: AppContainer, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberMediaPicker(c) { }
    if (settings.customMedia == null) {
        Text(stringResource(R.string.media_hint, MediaImporter.MAX_VIDEO_SECONDS), color = BrColors.TestoSecondario, fontSize = 13.sp)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = pick, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.media_change), color = BrColors.Accento)
        }
        TextButton(
            onClick = {
                scope.launch {
                    c.settings.clearCustomMedia()
                    c.cleanupMedia()
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.media_remove), color = BrColors.TestoSecondario) }
    }
}
