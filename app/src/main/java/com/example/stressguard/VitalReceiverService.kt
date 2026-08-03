package com.example.stressguard

import android.os.SystemClock
import android.util.Log
import com.example.stressguard.data.PipelineResult
import com.example.stressguard.data.SensorReading
import com.example.stressguard.data.StressPipeline
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives vitals from the watch over the Wearable message channel.
 *
 * The arrival timestamp is taken first, before decryption and parsing, so the latency figures
 * include the work this service does rather than starting the clock after it.
 *
 * Play Services starts this service on message arrival, which is what lets the app monitor with
 * nothing open. It therefore drives [StressPipeline] directly rather than publishing for a
 * dashboard to pick up: with the watch delivering batches in the background, most readings arrive
 * when no Activity exists at all.
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

        // Inference runs here, not in the dashboard. Vitals arrive while the app is closed --
        // that is the whole point of the watch's passive listener -- and when this work lived in
        // DashboardViewModel a reading with no Activity open was received and then discarded.
        //
        // runBlocking rather than launching and returning: onMessageReceived is already called on
        // a background thread, and the service may be torn down as soon as it returns, which
        // would cancel the inference partway through. Blocking here keeps the service alive for
        // the roughly 50-100 ms the pass takes.
        val result = runBlocking { StressPipeline.get(applicationContext).process(reading) }
        when (result) {
            is PipelineResult.Predicted -> Log.d(
                TAG,
                "predicted ${result.prediction.label} " +
                    "(${result.prediction.confidence}) from hr=${reading.heartRate}, " +
                    "sample was ${reading.sampleAgeMs} ms old"
            )
            is PipelineResult.PausedForWorkout -> Log.i(
                TAG,
                "workout mode active; skipped prediction for hr=${reading.heartRate}, " +
                    "steps=${reading.dailySteps}"
            )
            // Worth a warning rather than silence: with no dashboard open this is the only place
            // a background failure is visible at all.
            is PipelineResult.Failed -> Log.w(TAG, "could not predict: ${result.message}")
        }
    }

    companion object {
        private const val TAG = "VITALS"
        private const val VITALS_PATH = "/stress_vitals"
    }
}
