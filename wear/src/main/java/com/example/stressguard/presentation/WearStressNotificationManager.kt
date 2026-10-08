package com.example.stressguard.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.stressguard.R

/** Posts the watch-owned OS notification for a phone-confirmed sustained-stress alert. */
object WearStressNotificationManager {
    private const val TAG = "WEAR_STRESS_NOTICE"
    private const val CHANNEL_ID = "stress_alerts"
    private const val NOTIFICATION_ID = 2101

    private val alertVibrationPattern = longArrayOf(0, 220, 140, 220)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.stress_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.stress_notification_channel_description)
            enableVibration(true)
            vibrationPattern = alertVibrationPattern
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Displays one system notification for a new alert. The caller prevents duplicate alert IDs;
     * replacing the fixed notification ID keeps the watch shade from filling with stale alerts.
     */
    fun show(context: Context, state: WatchProtocol.State): Boolean {
        if (!state.alertActive || state.feedbackId <= 0L) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notification permission not granted; alert ${state.feedbackId} not shown")
            return false
        }

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val openApp = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_STRESS_ALERT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val scoreText = state.score?.let {
            context.getString(R.string.stress_notification_score, it)
        } ?: context.getString(R.string.stress_notification_no_score)
        val detail = context.getString(
            R.string.stress_notification_detail,
            scoreText,
            state.label.lowercase().replaceFirstChar { it.titlecase() },
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stress_notification)
            .setContentTitle(context.getString(R.string.stress_notification_title))
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(state.measuredAtEpochMs > 0L)
            .setWhen(state.measuredAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis())
            .build()

        // A different alert should buzz even if the previous notification was never dismissed.
        manager.cancel(NOTIFICATION_ID)
        manager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "posted watch notification for alert ${state.feedbackId}")
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    const val ACTION_OPEN_STRESS_ALERT =
        "com.example.stressguard.action.OPEN_STRESS_ALERT"
}
