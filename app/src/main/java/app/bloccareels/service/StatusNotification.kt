package app.bloccareels.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.bloccareels.R
import app.bloccareels.ui.MainActivity

/**
 * The quiet, ongoing "Reels Killer is on" notification: it exists only while the service runs
 * and blocking is on. From Android 14 the user can swipe it away; its delete intent broadcasts
 * [ACTION_DISMISSED] and the service then calls [onDismissed] to put it back.
 */
class StatusNotification(
    private val context: Context,
    /** Context in the language chosen in the app's settings. */
    private val localized: () -> Context,
    private val onError: (String, Throwable) -> Unit,
) {
    private var shown = false

    // Looked up on each use: the service creates this object before its context is attached.
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun update(shouldShow: Boolean) {
        if (shouldShow) show() else cancel()
    }

    /** The user swiped the notification away: show it again if it should be there. */
    fun onDismissed(shouldShow: Boolean) {
        shown = false
        update(shouldShow)
    }

    fun cancel() {
        try {
            nm.cancel(NOTIFICATION_ID)
            nm.cancel(OLD_DEBUG_NOTIFICATION_ID)
        } catch (e: Exception) {
            onError("notification", e)
        }
        shown = false
    }

    private fun show() {
        if (shown) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = localized()
        // Channel of the old debug notification, no longer used.
        try {
            nm.cancel(OLD_DEBUG_NOTIFICATION_ID)
            nm.deleteNotificationChannel(OLD_DEBUG_CHANNEL_ID)
        } catch (e: Exception) {
            onError("debug channel", e)
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.status_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                description = text.getString(R.string.status_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, REQUEST_DISMISSED,
            Intent(ACTION_DISMISSED).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.getString(R.string.status_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        shown = true
    }

    companion object {
        /** Broadcast sent when the user swipes the notification away. */
        const val ACTION_DISMISSED = "app.bloccareels.action.NOTIFICATION_DISMISSED"

        /** Channel id kept from older versions ("stato" = status), so no duplicate channel appears. */
        private const val CHANNEL_ID = "stato"
        private const val NOTIFICATION_ID = 2
        private const val OLD_DEBUG_CHANNEL_ID = "debug"
        private const val OLD_DEBUG_NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 2
        private const val REQUEST_DISMISSED = 3
    }
}
