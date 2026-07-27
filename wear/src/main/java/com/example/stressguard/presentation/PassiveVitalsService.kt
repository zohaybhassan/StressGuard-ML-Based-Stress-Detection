package com.example.stressguard.presentation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives batched vitals from Health Services and forwards them to the phone.
 *
 * Bound on demand, so it runs whether or not [MainActivity] exists. This is what makes the app a
 * background monitor rather than something the user has to hold open: the registration lives in
 * Health Services (see [PassiveVitals]), and this service is started to take delivery of each
 * batch even after the process has been killed.
 *
 * Because the process does not persist, nothing may be held in memory between deliveries. The
 * daily step total in particular has to be stored: a batch can carry heart rate with no steps, or
 * steps with no heart rate, and a reading needs both.
 */
class PassiveVitalsService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val store = PassiveVitalsStore(this)

        val now = System.currentTimeMillis()

        // Steps first, so a batch carrying both uses the value from this batch rather than the
        // one before it. STEPS_DAILY is the platform's own count since midnight and the most
        // trustworthy figure available; the store keeps the highest seen today so the Activity's
        // weaker estimate cannot overwrite it.
        dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { point ->
            store.recordSteps(point.value.toInt(), now)
            Log.d(TAG, "daily steps now ${point.value}")
        }

        val samples = dataPoints.getData(DataType.HEART_RATE_BPM)
        val newest = samples
            .filter { it.value > 0.0 }
            .maxByOrNull { it.timeDurationFromBoot }

        if (newest == null) {
            // Normal, not an error: a steps-only batch, or the watch was off the wrist for the
            // whole window so every sample is zero.
            Log.d(TAG, "batch of ${samples.size} heart rate samples carried nothing usable")
            return
        }

        // Only the newest sample is forwarded. A batch can span minutes and the phone stamps
        // arrival time, so sending the older samples too would file them all under the same
        // instant -- several rows of history claiming to be simultaneous, and an alert window
        // filled from one delivery.
        val heartRate = newest.value.toInt()
        val steps = store.stepsToday(now)

        // How stale the sample already is at the moment of sending. Sent as a duration, not a
        // timestamp: the two devices' clocks are not synchronised, but an elapsed time measured
        // on the watch means the same thing on the phone.
        val ageMs = (SystemClock.elapsedRealtime() - newest.timeDurationFromBoot.toMillis())
            .coerceAtLeast(0L)

        Log.d(
            TAG,
            "batch of ${samples.size}: newest heart rate $heartRate, ${ageMs} ms old, steps $steps"
        )

        if (!store.claimSendSlot()) {
            Log.d(TAG, "throttled; $heartRate|$steps not sent")
            return
        }

        send(this, "$heartRate|$steps|$ageMs")
    }

    /**
     * Health Services calls this when the heart rate permission is withdrawn while registered.
     *
     * The registration has to be cleared, or Health Services keeps binding this service to
     * deliver data it is no longer allowed to collect. The next app launch re-registers if the
     * permission comes back.
     */
    override fun onPermissionLost() {
        Log.w(TAG, "heart rate permission withdrawn; clearing the background registration")
        PassiveVitalsStore(this).registered = false

        // Not merely a flag: leaving the registration in place has Health Services keep binding
        // this service to deliver data it is no longer allowed to collect. The next app launch
        // registers again if the permission comes back.
        val appContext = applicationContext
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            PassiveVitals.unregister(appContext)
        }
    }

    companion object {
        private const val TAG = PassiveVitals.TAG

        /**
         * Sends one reading to every connected phone.
         *
         * Deliberately a plain function taking a [Context] rather than a method: the service
         * instance may be gone by the time the send completes, and nothing here needs it.
         */
        fun send(
            context: Context,
            payload: String,
            onNoPhone: () -> Unit = {},
            onSent: () -> Unit = {},
        ) {
            val encrypted = EncryptionUtil.encrypt(payload)
            val messageClient = Wearable.getMessageClient(context)

            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (nodes.isEmpty()) {
                        // Expected while the phone is out of range. The reading is dropped rather
                        // than queued: a stress reading is only useful promptly, and the phone
                        // would stamp a replayed one with the wrong arrival time.
                        Log.w(TAG, "no connected phone; $payload not sent")
                        onNoPhone()
                        return@addOnSuccessListener
                    }
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, VITALS_PATH, encrypted)
                            .addOnSuccessListener {
                                Log.d(TAG, "sent $payload to ${node.displayName}")
                                onSent()
                            }
                            .addOnFailureListener {
                                Log.w(TAG, "send to ${node.displayName} failed: ${it.message}")
                            }
                    }
                }
                .addOnFailureListener { Log.w(TAG, "could not list connected nodes: ${it.message}") }
        }

        const val VITALS_PATH = "/stress_vitals"
    }
}
