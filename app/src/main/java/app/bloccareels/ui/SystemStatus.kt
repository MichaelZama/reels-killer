package app.bloccareels.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import app.bloccareels.R
import app.bloccareels.service.ReelsBlockerService

/** Stato del sistema e scorciatoie verso le schermate di sistema. */
object SystemStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ReelsBlockerService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openAccessibilitySettings(context: Context) =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppInfo(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (!start(context, direct, toastOnFailure = false)) {
            start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Xiaomi, Redmi e POCO (MIUI / HyperOS). */
    fun isXiaomi(): Boolean =
        android.os.Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /**
     * Apre la pagina "Avvio automatico" di MIUI/HyperOS. Senza questo permesso il sistema
     * non riavvia il servizio dopo una chiusura o un aggiornamento. Se la pagina non esiste
     * si ripiega sulle informazioni dell'app.
     */
    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"),
        )
        for (cn in candidates) {
            val i = Intent().setComponent(cn)
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", context.getString(R.string.app_name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, i, toastOnFailure = false)) return
        }
        openAppInfo(context)
    }

    fun start(context: Context, intent: Intent, toastOnFailure: Boolean = true): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (toastOnFailure) Toast.makeText(context, R.string.error_no_activity, Toast.LENGTH_SHORT).show()
            false
        } catch (_: SecurityException) {
            if (toastOnFailure) Toast.makeText(context, R.string.error_no_activity, Toast.LENGTH_SHORT).show()
            false
        }
}
