package com.fugazi.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fugazi.app.R
import com.fugazi.app.ui.InterventionActivity

/** Posts the one notification this app exists to send. */
object InterventionNotifier {

    private const val CHANNEL_INTERVENTION = "intervention"
    private const val CHANNEL_STATUS = "status"
    private const val ID_INTERVENTION = 1
    private const val ID_BLIND = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INTERVENTION,
                "The one nudge",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires once, early, when the valley is starting." },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Radar status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Quiet notices, e.g. if usage access gets turned off." },
        )
    }

    /** The trip fired. One title, one line, tap to open the one screen. */
    fun notifyIntervention(context: Context, keystone: String, message: String) {
        val intent = Intent(context, InterventionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(InterventionActivity.EXTRA_MESSAGE, message)
            putExtra(InterventionActivity.EXTRA_KEYSTONE, keystone)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_INTERVENTION)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Catch it now")
            .setContentText(keystone)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        safeNotify(context, ID_INTERVENTION, n)
    }

    /** Usage access is off, so the radar is blind. Not a nag — a config integrity notice. */
    fun notifyBlind(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Radar is blind")
            .setContentText("Usage access is off — fugazi can't watch for the valley.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        safeNotify(context, ID_BLIND, n)
    }

    private fun safeNotify(context: Context, id: Int, n: android.app.Notification) {
        val nmc = NotificationManagerCompat.from(context)
        if (nmc.areNotificationsEnabled()) {
            try {
                nmc.notify(id, n)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; nothing more we can do here.
            }
        }
    }
}
