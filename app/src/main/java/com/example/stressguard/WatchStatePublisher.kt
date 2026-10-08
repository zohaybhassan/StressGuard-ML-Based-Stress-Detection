package com.example.stressguard

import android.content.Context
import android.util.Log
import com.example.stressguard.data.PipelineResult
import com.example.stressguard.data.WorkoutSessionRepository
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.WorkoutSessionStatus
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable

/** Builds and delivers the phone-owned stress result to the watch that supplied the reading. */
object WatchStatePublisher {
    private const val TAG = "WATCH_STATE"

    suspend fun publish(
        context: Context,
        nodeId: String,
        result: PipelineResult? = null,
        feedbackId: Long = 0L,
    ): WatchProtocol.State {
        val state = buildState(context, result, feedbackId)
        val encrypted = EncryptionUtil.encrypt(state.encode())
        runCatching {
            val requestId = Tasks.await(
                Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WatchProtocol.STATE_PATH, encrypted)
            )
            Log.i(
                TAG,
                "sent state requestId=$requestId score=${state.score} " +
                    "measuredAt=${state.measuredAtEpochMs} alert=${state.alertActive}"
            )
        }.onFailure { Log.w(TAG, "could not send stress state to the watch", it) }
        return state
    }

    suspend fun buildState(
        context: Context,
        result: PipelineResult? = null,
        feedbackId: Long = 0L,
    ): WatchProtocol.State {
        val database = StressGuardDatabase.get(context)
        val alertSnapshot = feedbackId.takeIf { it > 0L }?.let { id ->
            runCatching { database.stressFeedback().byId(id) }.getOrNull()
        }
        val stored = when (result) {
            is PipelineResult.Predicted -> null
            else -> runCatching { database.stressPredictions().latest(1).firstOrNull() }.getOrNull()
        }
        val workout = runCatching { WorkoutSessionRepository.current(context) }.getOrNull()

        // The dashboard is a live mirror of the phone. An unanswered alert keeps its feedback ID
        // so the check-in remains actionable, but must never pin the gauge and its timestamp to
        // the historical prediction that originally fired the alert.
        val livePrediction = result as? PipelineResult.Predicted
        val probabilities = livePrediction?.prediction?.probabilities
            ?: stored?.probabilities?.toFloatArray()
            ?: alertSnapshot?.probabilities?.toFloatArray()
        val rawLabel = livePrediction?.prediction?.label
            ?: stored?.label
            ?: alertSnapshot?.predictedLabel
        val classIndex = livePrediction?.prediction?.classIndex
            ?: stored?.classIndex
            ?: alertSnapshot?.predictedClassIndex
        val measuredAt = when (result) {
            is PipelineResult.Predicted -> result.reading.measuredAtEpochMs
            is PipelineResult.PausedForWorkout -> result.reading.measuredAtEpochMs
            else -> stored?.recordedAtEpochMs
                ?: alertSnapshot?.predictionRecordedAtEpochMs
                ?: 0L
        }
        val outOfRange = livePrediction?.extrapolating
            ?: stored?.outOfTrainingRange
            ?: alertSnapshot?.outOfTrainingRange
            ?: false

        val status = if (alertSnapshot != null) WatchProtocol.Status.READY else when (result) {
            is PipelineResult.PausedForWorkout -> WatchProtocol.Status.WORKOUT
            is PipelineResult.Failed -> when (result.message) {
                "PROFILE NEEDED" -> WatchProtocol.Status.PROFILE_NEEDED
                else -> WatchProtocol.Status.MODEL_ERROR
            }
            else -> if (probabilities != null && rawLabel != null && classIndex != null) {
                if (workout != null && !workout.isCompleted) WatchProtocol.Status.WORKOUT
                else WatchProtocol.Status.READY
            } else {
                WatchProtocol.Status.WAITING
            }
        }

        val workoutStatus = when (workout?.status) {
            WorkoutSessionStatus.ACTIVE -> WatchProtocol.WorkoutStatus.ACTIVE
            WorkoutSessionStatus.PAUSED -> WatchProtocol.WorkoutStatus.PAUSED
            else -> WatchProtocol.WorkoutStatus.NONE
        }
        // A check-in remains actionable until the user answers it. Do not clear it merely because
        // a newer reading was lower, or because either app process restarted in the meantime.
        val alertActive = feedbackId > 0L

        return WatchProtocol.State(
            status = status,
            score = probabilities?.let(StressDisplay::score),
            label = rawLabel?.let(StressDisplay::label) ?: "WAITING",
            severity = if (classIndex != null && probabilities != null) {
                StressDisplay.severity(classIndex, probabilities.size)
            } else {
                StressSeverity.UNKNOWN
            },
            measuredAtEpochMs = measuredAt,
            outOfTrainingRange = outOfRange,
            alertsMutedUntilEpochMs = SessionManager.getAlertsMutedUntil(context),
            workoutUntilEpochMs = workout?.plannedEndAtEpochMs
                ?: SessionManager.getWorkoutModeUntil(context),
            workoutStatus = workoutStatus,
            feedbackId = feedbackId,
            alertActive = alertActive,
        )
    }
}
