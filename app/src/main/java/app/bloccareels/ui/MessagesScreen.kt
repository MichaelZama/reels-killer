package app.bloccareels.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.data.CustomMedia
import app.bloccareels.data.MediaImporter
import app.bloccareels.data.MediaKind
import app.bloccareels.data.Settings
import kotlinx.coroutines.launch

private val LEVEL_THUMB = 56.dp

/**
 * Livelli della schermata di blocco: livello 1 = primo blocco, livello 2 = dopo il primo "Continua"...
 * Ogni livello ha un messaggio e, se si vuole, un'immagine, una GIF o un video.
 * Un livello senza media usa quello del livello precedente.
 */
@Composable
fun MessagesScreen(c: AppContainer, initial: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val texts = remember { mutableStateListOf<String>().apply { addAll(initial.messages) } }
    val media = remember {
        mutableStateListOf<CustomMedia?>().apply { addAll(List(initial.messages.size) { initial.levelMedia.getOrNull(it) }) }
    }
    val defaults = stringArrayResource(R.array.default_messages).toList()

    fun persist() {
        val t = texts.toList()
        val m = media.toList()
        c.appScope.launch {
            c.settings.setLevels(t, m)
            c.cleanupMedia()
        }
    }

    // Selettore di media condiviso: [pickTarget] è il livello che riceve il file.
    var pickTarget by remember { mutableIntStateOf(-1) }
    var busyLevel by remember { mutableIntStateOf(-1) }
    val msgTooLong = stringResource(R.string.media_too_long, MediaImporter.MAX_VIDEO_SECONDS)
    val msgTooBig = stringResource(R.string.media_too_big)
    val msgUnsupported = stringResource(R.string.media_unsupported)
    val msgFailed = stringResource(R.string.media_failed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickTarget
        if (uri == null || target !in media.indices) return@rememberLauncherForActivityResult
        busyLevel = target
        scope.launch {
            val msg = when (val r = MediaImporter.import(context, uri)) {
                is MediaImporter.Result.Ok -> {
                    if (target in media.indices) media[target] = r.media
                    persist()
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
            busyLevel = -1
            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("‹ " + stringResource(R.string.back), color = BrColors.Accento)
        }
        Text(
            stringResource(R.string.messages_title),
            color = BrColors.Testo,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(start = 4.dp)
                .semantics { heading() },
        )
        Text(
            stringResource(R.string.messages_hint, MediaImporter.MAX_VIDEO_SECONDS),
            color = BrColors.TestoSecondario,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp),
        )

        texts.forEachIndexed { index, text ->
            // Media effettivo del livello, anche se ereditato dai precedenti.
            val own = media.getOrNull(index)
            val inherited = if (own == null) (index - 1 downTo 0).firstNotNullOfOrNull { media.getOrNull(it) } ?: initial.activeCustomMedia else null
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(BrColors.Superficie)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (index == 0) R.string.level_first else R.string.level_n, index + 1, index),
                    color = BrColors.TestoSecondario,
                    fontSize = 13.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelMediaThumb(
                        media = own,
                        inherited = inherited,
                        busy = busyLevel == index,
                        description = stringResource(R.string.level_media_description, index + 1),
                        onClick = {
                            pickTarget = index
                            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            texts[index] = v.replace('\n', ' ')
                            persist()
                        },
                        label = { Text(stringResource(R.string.message_field, index + 1)) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrColors.Accento,
                            unfocusedBorderColor = BrColors.Bordo,
                            focusedLabelColor = BrColors.Accento,
                            unfocusedLabelColor = BrColors.TestoSecondario,
                            cursorColor = BrColors.Accento,
                            focusedTextColor = BrColors.Testo,
                            unfocusedTextColor = BrColors.Testo,
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (own != null) {
                        TextButton(
                            onClick = {
                                media[index] = null
                                persist()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.level_media_remove), color = BrColors.TestoSecondario) }
                    }
                    val removeLabel = stringResource(R.string.message_remove, index + 1)
                    TextButton(
                        onClick = {
                            texts.removeAt(index)
                            media.removeAt(index)
                            persist()
                        },
                        enabled = texts.size > 1,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = removeLabel },
                    ) {
                        Text(
                            stringResource(R.string.level_remove),
                            color = if (texts.size > 1) BrColors.TestoSecondario else BrColors.Bordo,
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = {
                texts.add("")
                media.add(null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text("+ " + stringResource(R.string.level_add), color = BrColors.Accento) }

        TextButton(
            onClick = {
                texts.clear()
                texts.addAll(defaults)
                media.clear()
                media.addAll(List(defaults.size) { null })
                c.appScope.launch {
                    c.settings.resetMessages()
                    c.cleanupMedia()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.messages_reset), color = BrColors.TestoSecondario) }
    }
}

/** Miniatura del media di un livello: proprio, ereditato (sbiadito) oppure "+". */
@Composable
private fun LevelMediaThumb(
    media: CustomMedia?,
    inherited: CustomMedia?,
    busy: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val shown = media ?: inherited
    val sizePx = with(LocalDensity.current) { LEVEL_THUMB.roundToPx() }
    val thumb: Bitmap? by produceState<Bitmap?>(null, shown) {
        value = shown?.let { MediaImporter.thumbnail(it, sizePx) }
    }
    Box(
        modifier = Modifier
            .size(LEVEL_THUMB)
            .clip(RoundedCornerShape(14.dp))
            .background(BrColors.Sfondo)
            .border(1.dp, if (media != null) BrColors.Accento else BrColors.Bordo, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumb
        when {
            busy -> Text("…", color = BrColors.TestoSecondario, fontSize = 20.sp)
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (media != null) 1f else 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!busy && media == null) Text("+", color = BrColors.Accento, fontSize = 24.sp)
        if (!busy && media?.kind == MediaKind.VIDEO && bmp != null) Text("▶", color = BrColors.Testo, fontSize = 14.sp)
    }
}
