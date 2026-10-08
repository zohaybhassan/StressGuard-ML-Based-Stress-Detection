package com.example.stressguard.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** ADB-only notification check; it does not change stored stress or feedback state. */
class DebugStressAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SHOW) return
        WearStressNotificationManager.show(
            context,
            WatchProtocol.State(
                status = WatchProtocol.Status.READY,
                score = 92,
                label = "TEST HIGH STRESS",
                severity = WatchProtocol.Severity.HIGH,
                measuredAtEpochMs = System.currentTimeMillis(),
                feedbackId = System.currentTimeMillis(),
                alertActive = true,
            ),
        )
    }

    companion object {
        private const val ACTION_SHOW =
            "com.example.stressguard.debug.SHOW_HIGH_STRESS_ALERT"
    }
}
