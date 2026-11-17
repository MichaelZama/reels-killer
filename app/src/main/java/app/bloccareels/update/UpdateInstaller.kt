package app.bloccareels.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.bloccareels.R
import app.bloccareels.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs an APK picked by the user with Android's session-based installer.
 * Apps updated this way do not get the "restricted settings" again, which Android
 * applies instead to installs opened from the browser or the file manager.
 * No INTERNET permission is needed: the user downloads the APK.
 */
object UpdateInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen that lets this app install updates. */
    fun openInstallPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.error_no_activity, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies the APK into an install session and commits it; the result arrives at [InstallResultReceiver]. */
    suspend fun install(context: Context, apk: Uri) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            context.contentResolver.openInputStream(apk).use { input ->
                requireNotNull(input) { "File not readable" }
                session.openWrite("update.apk", 0, -1).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the install result and, if needed, shows the system confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        context.container.diagnostics.error("Install confirmation", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the system restarts the app
            else -> {
                context.container.diagnostics.error("Update install: status $status ${message.orEmpty()}")
                Toast.makeText(
                    context,
                    context.getString(R.string.update_failed, message ?: status.toString()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
