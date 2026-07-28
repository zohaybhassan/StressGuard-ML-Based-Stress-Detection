package com.example.stressguard.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.stressguard.AssistantActivity
import com.example.stressguard.HomeDashboardActivity
import com.example.stressguard.R
import com.example.stressguard.data.local.AlertEventDao
import com.example.stressguard.data.local.AlertEventEntity

/**
 * Turns a sustained run of high-stress predictions into something the user notices.
 *
 * The decision lives in [StressAlertPolicy] and is pure; this class only carries it out —
 * vibrate, notify, record. Splitting them keeps the rule testable without a device, which
 * matters because the rule is the part that is easy to get subtly wrong.
 *
 * Cooldown state is read from the database rather than held in memory, so killing the app
 * cannot be used to bypass it and a restart does not produce an immediate repeat alert.
 */
class StressAlertManager(
    private val context: Context,
    private val dao: AlertEventDao,
) {

    /**
     * @return the decision taken, so the caller can mark the latency sample and the dashboard
     *   can explain why nothing happened.
     */
    suspend fun onPrediction(
        recentClassIndices: List<Int>,
        highStressClassIndex: Int,
        modelVersion: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AlertDecision {
        val decision = StressAlertPolicy.evaluate(
            recentClassIndices = recentClassIndices,
            highStressClassIndex = highStressClassIndex,
            nowEpochMs = nowEpochMs,
            lastAlertEpochMs = dao.mostRecent()?.firedAtEpochMs,
        )

        if (decision !is AlertDecision.Fire) {
            if (decision is AlertDecision.InCooldown) {
                Log.d(TAG, "sustained stress, suppressed for ${decision.remainingMs / 1000}s more")
            }
            return decision
        }

        dao.insert(
            AlertEventEntity(
                firedAtEpochMs = nowEpochMs,
                reason = decision.reason,
                highCountInWindow = decision.highCount,
                windowSize = decision.windowSize,
                modelVersion = modelVersion,
            )
        )

        vibrate()
        notify(decision.reason)
        Log.i(TAG, "alert fired: ${decision.reason}")
        return decision
    }

    /**
     * Two firm pulses rather than one long buzz: distinguishable from a message notification
     * without being alarming, which matters for something telling you that you are stressed.
     */
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator?.hasVibrator() != true) {
            Log.d(TAG, "no vibrator on this device; alert is visual only")
            return
        }

        val timings = longArrayOf(0, 400, 200, 400)
        val amplitudes = intArrayOf(0, 200, 0, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    private fun notify(reason: String) {
        // POST_NOTIFICATIONS is runtime-granted from API 33. Without it, notify() is a silent
        // no-op, so check rather than assume: the vibration still happened and the alert is
        // still recorded, and the dashboard shows it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "notification permission not granted; alert was haptic only")
            return
        }

        createChannel()

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, HomeDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Plan §18 asks for a quick action from a high-stress alert into the chatbot. It matters
        // more than it sounds: the alert fires when someone is least inclined to go looking for
        // help, so the distance between "you are stressed" and "here is someone to talk to"
        // should be one tap, not a hunt through tabs.
        val talk = PendingIntent.getActivity(
            context,
            REQUEST_TALK,
            AssistantActivity.fromAlert(context, stressLabel = "high")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sustained high stress")
            // Deliberately not diagnostic language, per plan §25.
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason. Consider taking a short break."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Talk it through", talk)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "could not post the alert notification", it) }
    }

    private fun createChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stress alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when several recent readings indicate sustained high stress."
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "STRESS_ALERT"
        private const val CHANNEL_ID = "stress_alerts"
        private const val NOTIFICATION_ID = 1001

        /** Distinct from the content intent's request code, or the two PendingIntents collide. */
        private const val REQUEST_TALK = 1
    }
}
