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
            Tasks.await(
                Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WatchProtocol.STATE_PATH, encrypted)
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

        // Keep an active alert tied to the exact prediction that caused it. Live readings may
        // continue arriving while the check-in is waiting; they must not turn a HIGH alert into
        // a NORMAL gauge before the user has responded.
        val probabilities = alertSnapshot?.probabilities?.toFloatArray()
            ?: (result as? PipelineResult.Predicted)?.prediction?.probabilities
            ?: stored?.probabilities?.toFloatArray()
        val rawLabel = alertSnapshot?.predictedLabel
            ?: (result as? PipelineResult.Predicted)?.prediction?.label
            ?: stored?.label
        val classIndex = alertSnapshot?.predictedClassIndex
            ?: (result as? PipelineResult.Predicted)?.prediction?.classIndex
            ?: stored?.classIndex
        val measuredAt = alertSnapshot?.predictionRecordedAtEpochMs ?: when (result) {
            is PipelineResult.Predicted -> result.reading.measuredAtEpochMs
            is PipelineResult.PausedForWorkout -> result.reading.measuredAtEpochMs
            else -> stored?.recordedAtEpochMs ?: 0L
        }
        val outOfRange = alertSnapshot?.outOfTrainingRange
            ?: (result as? PipelineResult.Predicted)?.extrapolating
            ?: stored?.outOfTrainingRange
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
