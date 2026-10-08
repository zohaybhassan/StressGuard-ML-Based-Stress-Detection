package com.example.stressguard.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives stress results and control state from the phone. Inference remains phone-owned. */
class WatchStateListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != WatchProtocol.STATE_PATH) return

        val payload = runCatching { EncryptionUtil.decrypt(messageEvent.data) }
            .onFailure { Log.w(TAG, "could not decrypt phone state", it) }
            .getOrNull() ?: return
        val previousAlert = WatchStateStore.state(applicationContext).value.state.feedbackId
        if (!WatchStateStore.update(applicationContext, payload, System.currentTimeMillis())) {
            Log.w(TAG, "discarded malformed phone state")
            return
        }
        val state = WatchStateStore.state(applicationContext).value.state
        Log.i(
            TAG,
            "received state score=${state.score} measuredAt=${state.measuredAtEpochMs} " +
                "alert=${state.alertActive}"
        )
        if (state.alertActive && state.feedbackId > 0L && state.feedbackId != previousAlert) {
            WearStressNotificationManager.show(applicationContext, state)
        } else if (!state.alertActive) {
            WearStressNotificationManager.cancel(applicationContext)
        }
    }

    companion object {
        private const val TAG = "WATCH_STATE"
    }
}
