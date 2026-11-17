package app.bloccareels.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.bloccareels.AppContainer
import app.bloccareels.R
import app.bloccareels.update.UpdateInstaller
import kotlinx.coroutines.launch

/** Releases page: the user downloads new versions from here. */
const val REPO_URL = "https://github.com/MichaelZama/reels-killer"
const val RELEASES_URL = "$REPO_URL/releases/latest"

/** Installs updates from a downloaded APK, without "restricted settings". */
@Composable
fun UpdateSection(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    var busy by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canInstall = UpdateInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    val failed = stringResource(R.string.update_failed, "")

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                UpdateInstaller.install(context, uri)
            } catch (e: Exception) {
                c.diagnostics.error("Update install", e)
                Toast.makeText(context, failed + (e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    SectionCard(stringResource(R.string.section_update)) {
        Text(stringResource(R.string.update_version, version), color = AppColors.TextPrimary, fontSize = 15.sp)
        Text(stringResource(R.string.update_where), color = AppColors.TextSecondary, fontSize = 13.sp)
        TextButton(
            onClick = {
                SystemStatus.start(context, Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.update_open_releases), color = AppColors.Accent, fontSize = 15.sp)
        }
        Text(
            stringResource(if (canInstall) R.string.update_note else R.string.update_permission_note),
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
        )
        Button(
            onClick = {
                if (!canInstall) {
                    UpdateInstaller.openInstallPermission(context)
                } else {
                    picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Background, contentColor = AppColors.TextPrimary),
        ) {
            Text(
                stringResource(
                    when {
                        busy -> R.string.update_installing
                        canInstall -> R.string.update_button
                        else -> R.string.update_permission_button
                    },
                ),
            )
        }
    }
}
