package app.bloccareels.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bloccareels.AppContainer
import app.bloccareels.ServiceCommand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import app.bloccareels.R
import app.bloccareels.data.Settings
import app.bloccareels.diag.LogEntry
import app.bloccareels.diag.LogType
import app.bloccareels.diag.TreeDumper
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val EXPORT_DELAY_SECONDS = 10

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Sezione di diagnostica (sezione 11), chiusa di default. */
@Composable
fun DiagnosticsSection(c: AppContainer, settings: Settings) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val entries by c.diagnostics.entries.collectAsStateWithLifecycle()
    val status by c.diagnostics.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(start = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.section_diagnostics),
                color = BrColors.TestoSecondario,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Text(
                stringResource(if (expanded) R.string.diag_collapse else R.string.diag_expand),
                color = BrColors.Accento,
                fontSize = 14.sp,
            )
        }
        if (!expanded) return@Column

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BrColors.Superficie)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val notConnected = stringResource(R.string.diag_export_not_connected)
            Text(stringResource(R.string.diag_export_note), color = BrColors.TestoSecondario, fontSize = 13.sp)
            Button(
                onClick = {
                    if (!status.serviceConnected) {
                        Toast.makeText(context, notConnected, Toast.LENGTH_LONG).show()
                    } else {
                        c.serviceCommands.tryEmit(ServiceCommand.ExportTree(EXPORT_DELAY_SECONDS))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrColors.Sfondo, contentColor = BrColors.Testo),
            ) { Text(stringResource(R.string.diag_export_button, EXPORT_DELAY_SECONDS)) }

            val none = stringResource(R.string.diag_none)
            InfoLine(stringResource(R.string.diag_service, stringResource(if (status.serviceConnected) R.string.yes else R.string.no)))
            InfoLine(stringResource(R.string.diag_last_package, status.lastPackage ?: none))
            InfoLine(stringResource(R.string.diag_last_event, status.lastEventAtMs?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: none))
            InfoLine(stringResource(R.string.diag_state, status.controllerState))
            InfoLine(stringResource(R.string.diag_detection, status.lastDetection))

            val typeLabels = mapOf(
                LogType.EVENTO to stringResource(R.string.log_type_evento),
                LogType.RILEVAMENTO to stringResource(R.string.log_type_rilevamento),
                LogType.TRANSIZIONE to stringResource(R.string.log_type_transizione),
                LogType.ERRORE to stringResource(R.string.log_type_errore),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrColors.Sfondo)
                    .padding(8.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.diag_log_empty), color = BrColors.TestoSecondario, fontSize = 13.sp)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        entries.forEach { e ->
                            Text(
                                formatEntry(e, typeLabels),
                                color = if (e.type == LogType.ERRORE) BrColors.Avviso else BrColors.Testo,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            val noTree = stringResource(R.string.diag_no_tree)
            val shareTitle = stringResource(R.string.diag_share_title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DiagButton(stringResource(R.string.diag_share_log)) {
                    val text = entries.joinToString("\n") { formatEntry(it, typeLabels) }
                    val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "blocca-reels-log.txt")
                    file.writeText(text + "\n")
                    shareFile(context, file, shareTitle)
                }
                DiagButton(stringResource(R.string.diag_share_tree)) {
                    val f = TreeDumper.latest(context)
                    if (f == null) Toast.makeText(context, noTree, Toast.LENGTH_LONG).show()
                    else shareFile(context, f, shareTitle)
                }
                DiagButton(stringResource(R.string.diag_clear)) { c.diagnostics.clear() }
            }

            val version = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) {
                    "?"
                }
            }
            Text(
                stringResource(R.string.diag_versions, version, c.rules.version),
                color = BrColors.TestoSecondario,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(text, color = BrColors.Testo, fontSize = 13.sp)
}

@Composable
private fun DiagButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, color = BrColors.Accento, fontSize = 13.sp)
    }
}

private fun formatEntry(e: LogEntry, labels: Map<LogType, String>): String =
    "${timeFormat.format(Instant.ofEpochMilli(e.timeMs))} ${labels[e.type]} · ${e.text}"

private fun shareFile(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    SystemStatus.start(context, Intent.createChooser(send, title))
}
