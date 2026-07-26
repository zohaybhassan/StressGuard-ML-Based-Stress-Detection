package com.example.stressguard

import android.os.SystemClock
import android.util.Log
import com.example.stressguard.data.SensorReading
import com.example.stressguard.data.SensorRepository
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives vitals from the watch over the Wearable message channel.
 *
 * The arrival timestamp is taken first, before decryption and parsing, so the latency figures
 * include the work this service does rather than starting the clock after it.
 *
 * Readings go to [SensorRepository] rather than a broadcast: the dashboard is in this same
 * process, and a shared flow avoids serialising two integers into string extras only to parse
 * them back out again.
 */
class VitalReceiverService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != VITALS_PATH) return

        // First thing, before any processing: this is t=0 for the whole latency chain.
        val receivedAtElapsedMs = SystemClock.elapsedRealtime()
        val receivedAtEpochMs = System.currentTimeMillis()

        val payload = try {
            EncryptionUtil.decrypt(messageEvent.data)
        } catch (error: Exception) {
            Log.w(TAG, "could not decrypt a watch message; dropping it", error)
            return
        }

        val reading = SensorReading.parse(payload, receivedAtElapsedMs, receivedAtEpochMs)
        if (reading == null) {
            // Malformed, or values no person produces. Dropped rather than fed to the model,
            // where a bogus heart rate would yield a confident and meaningless prediction.
            SensorRepository.recordRejected()
            Log.w(TAG, "discarded an implausible or malformed reading")
            return
        }

        if (reading.outOfTrainingRange) {
            Log.d(
                TAG,
                "reading outside the trained range (hr=${reading.heartRate}, " +
                    "steps=${reading.dailySteps}); the model will extrapolate"
            )
        }

        SensorRepository.publish(reading)
    }

    companion object {
        private const val TAG = "VITALS"
        private const val VITALS_PATH = "/stress_vitals"
    }
}
