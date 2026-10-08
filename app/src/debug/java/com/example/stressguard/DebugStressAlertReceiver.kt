package com.example.stressguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** ADB-only visual alert check. It never writes predictions, trends, feedback, or sync data. */
class DebugStressAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SHOW) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stress alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Sustained high-stress alerts and debug checks." }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, HomeDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TEST · Sustained high stress")
            .setContentText("3 of the last 5 test readings indicated high stress")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "3 of the last 5 test readings indicated high stress. " +
                        "This is an ADB test and was not saved to your history."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400),
                intArrayOf(0, 200, 0, 200),
                -1,
            )
        )
    }

    companion object {
        private const val ACTION_SHOW =
            "com.example.stressguard.debug.SHOW_HIGH_STRESS_ALERT"
        private const val CHANNEL_ID = "stress_alerts"
        private const val TEST_NOTIFICATION_ID = 1099
    }
}
