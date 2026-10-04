package com.example.stressguard

import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import com.example.stressguard.data.PipelineResult
import com.example.stressguard.data.SensorReading
import com.example.stressguard.data.StressPipeline
import com.example.stressguard.data.WorkoutSessionRepository
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.sync.SyncScheduler
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
        when (messageEvent.path) {
            VITALS_PATH -> receiveVitals(messageEvent)
            WatchProtocol.COMMAND_PATH -> receiveCommand(messageEvent)
        }
    }

    private fun receiveVitals(messageEvent: MessageEvent) {

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

        runBlocking {
            val feedbackId = StressGuardDatabase.get(applicationContext).stressFeedback()
                .latestPending()?.id ?: 0L
            WatchStatePublisher.publish(
                context = applicationContext,
                nodeId = messageEvent.sourceNodeId,
                result = result,
                feedbackId = feedbackId,
            )
        }
    }

    private fun receiveCommand(messageEvent: MessageEvent) {
        val payload = runCatching { EncryptionUtil.decrypt(messageEvent.data) }
            .onFailure { Log.w(TAG, "could not decrypt a watch command", it) }
            .getOrNull() ?: return
        val command = WatchProtocol.Command.decode(payload) ?: run {
            Log.w(TAG, "discarded a malformed watch command")
            return
        }

        runBlocking {
            when (command) {
                WatchProtocol.Command.RequestState -> Unit
                is WatchProtocol.Command.MuteAlerts -> {
                    val duration = command.durationMs.coerceIn(MIN_MUTE_MS, MAX_MUTE_MS)
                    SessionManager.muteAlertsUntil(applicationContext, System.currentTimeMillis() + duration)
                }
                WatchProtocol.Command.ResumeAlerts -> SessionManager.clearAlertMute(applicationContext)
                is WatchProtocol.Command.StartWorkout -> WorkoutSessionRepository.start(
                    applicationContext,
                    command.durationMs.coerceIn(MIN_WORKOUT_MS, MAX_WORKOUT_MS),
                )
                WatchProtocol.Command.PauseWorkout ->
                    WorkoutSessionRepository.pause(applicationContext)
                WatchProtocol.Command.ResumeWorkout ->
                    WorkoutSessionRepository.resume(applicationContext)
                WatchProtocol.Command.EndWorkout ->
                    WorkoutSessionRepository.end(applicationContext)
                is WatchProtocol.Command.SaveFeedback -> saveFeedback(command)
            }

            val pendingFeedbackId = StressGuardDatabase.get(applicationContext).stressFeedback()
                .latestPending()?.id ?: 0L
            WatchStatePublisher.publish(
                context = applicationContext,
                nodeId = messageEvent.sourceNodeId,
                result = StressPipeline.get(applicationContext).latest.value,
                feedbackId = pendingFeedbackId,
            )
        }
    }

    private suspend fun saveFeedback(command: WatchProtocol.Command.SaveFeedback) {
        if (command.feedbackId <= 0L) return
        val database = StressGuardDatabase.get(applicationContext)
        val feedback = database.stressFeedback().byId(command.feedbackId) ?: return
        if (feedback.respondedAtEpochMs != null) return
        database.stressFeedback().recordResponse(
            id = command.feedbackId,
            confirmedStressed = command.confirmedStressed,
            severity = if (command.confirmedStressed) command.severity else null,
            respondedAtEpochMs = System.currentTimeMillis(),
        )
        database.alertEvents().markDismissed(feedback.alertEventId)
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
        SyncScheduler.syncNow(applicationContext)
    }

    companion object {
        private const val TAG = "VITALS"
        private const val VITALS_PATH = "/stress_vitals"
        private const val ALERT_NOTIFICATION_ID = 1001
        private const val MIN_MUTE_MS = 10 * 60_000L
        private const val MAX_MUTE_MS = 4 * 60 * 60_000L
        private const val MIN_WORKOUT_MS = 5 * 60_000L
        private const val MAX_WORKOUT_MS = 4 * 60 * 60_000L
    }
}
