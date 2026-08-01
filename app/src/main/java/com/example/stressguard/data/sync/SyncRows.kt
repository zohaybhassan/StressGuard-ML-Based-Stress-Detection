package com.example.stressguard.data.sync

import com.example.stressguard.data.local.AlertEventEntity
import com.example.stressguard.data.local.HealthChecklistEntity
import com.example.stressguard.data.local.LatencyMetricEntity
import com.example.stressguard.data.local.StressPredictionEntity
import com.example.stressguard.data.local.StressFeedbackEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The wire shapes for the synced tables.
 *
 * Separate from the Room entities on purpose. The local rows carry `id` and `synced`, which are
 * device-local bookkeeping and meaningless on the server; the server rows carry `user_id` and ISO
 * timestamps, which are meaningless locally. Mapping between them in one place keeps that
 * translation testable without a database or a network.
 *
 * Epoch millis become ISO-8601 strings because the Postgres columns are `timestamptz`: storing a
 * bigint would make the data unreadable in the Supabase table editor, which is where it will be
 * demonstrated.
 */

private fun Long.toIso(): String = Instant.ofEpochMilli(this).toString()

@Serializable
data class StressPredictionRow(
    @SerialName("user_id") val userId: String,
    @SerialName("recorded_at") val recordedAt: String,
    val label: String,
    @SerialName("class_index") val classIndex: Int,
    val confidence: Float,
    val probabilities: List<Float>,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("heart_rate") val heartRate: Int,
    @SerialName("daily_steps") val dailySteps: Int,
    @SerialName("activity_level") val activityLevel: Int,
    @SerialName("sleep_hours") val sleepHours: Float,
    @SerialName("out_of_training_range") val outOfTrainingRange: Boolean,
) {
    companion object {
        fun from(entity: StressPredictionEntity, userId: String) = StressPredictionRow(
            userId = userId,
            recordedAt = entity.recordedAtEpochMs.toIso(),
            label = entity.label,
            classIndex = entity.classIndex,
            confidence = entity.confidence,
            probabilities = entity.probabilities,
            modelVersion = entity.modelVersion,
            heartRate = entity.heartRate,
            dailySteps = entity.dailySteps,
            activityLevel = entity.activityLevel,
            sleepHours = entity.sleepHours,
            outOfTrainingRange = entity.outOfTrainingRange,
        )
    }
    /** A server row restored after login. It must not be queued for upload again. */
    fun toEntity(): StressPredictionEntity = StressPredictionEntity(
        recordedAtEpochMs = Instant.parse(recordedAt).toEpochMilli(),
        label = label,
        classIndex = classIndex,
        confidence = confidence,
        probabilities = probabilities,
        modelVersion = modelVersion,
        heartRate = heartRate,
        dailySteps = dailySteps,
        activityLevel = activityLevel,
        sleepHours = sleepHours,
        outOfTrainingRange = outOfTrainingRange,
        synced = true,
    )
}

@Serializable
data class LatencyMetricRow(
    @SerialName("user_id") val userId: String,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("preprocessing_ms") val preprocessingMs: Long,
    @SerialName("inference_ms") val inferenceMs: Long,
    @SerialName("ui_update_ms") val uiUpdateMs: Long,
    @SerialName("receive_to_prediction_ms") val receiveToPredictionMs: Long,
    /** Null when no alert fired for this sample, which is the common case. */
    @SerialName("prediction_to_alert_ms") val predictionToAlertMs: Long?,
    @SerialName("total_ms") val totalMs: Long,
    @SerialName("cold_start") val coldStart: Boolean,
) {
    companion object {
        fun from(entity: LatencyMetricEntity, userId: String) = LatencyMetricRow(
            userId = userId,
            recordedAt = entity.recordedAtEpochMs.toIso(),
            preprocessingMs = entity.preprocessingMs,
            inferenceMs = entity.inferenceMs,
            uiUpdateMs = entity.uiUpdateMs,
            receiveToPredictionMs = entity.receiveToPredictionMs,
            predictionToAlertMs = entity.predictionToAlertMs,
            totalMs = entity.totalMs,
            coldStart = entity.coldStart,
        )
    }
}

/**
 * The checklist's wire shape.
 *
 * Keyed on `user_id` alone rather than on a user-and-time pair like the history rows: this is the
 * user's current answers, so a re-save must land on the same row. That difference is what makes
 * the worker upsert this one with `onConflict = "user_id"`.
 */
@Serializable
data class HealthChecklistRow(
    @SerialName("user_id") val userId: String,
    val smoking: Boolean,
    @SerialName("heart_condition") val heartCondition: Boolean,
    val hypertension: Boolean,
    val diabetes: Boolean,
    @SerialName("sleep_disorder") val sleepDisorder: Boolean,
    @SerialName("anxiety_history") val anxietyHistory: Boolean,
    @SerialName("high_caffeine_use") val highCaffeineUse: Boolean,
    @SerialName("physically_inactive") val physicallyInactive: Boolean,
    @SerialName("updated_at") val updatedAt: String,
) {
    /** The local row this came from, for recovering a checklist after a reinstall. */
    fun toEntity(synced: Boolean = true) = HealthChecklistEntity(
        smoking = smoking,
        heartCondition = heartCondition,
        hypertension = hypertension,
        diabetes = diabetes,
        sleepDisorder = sleepDisorder,
        anxietyHistory = anxietyHistory,
        highCaffeineUse = highCaffeineUse,
        physicallyInactive = physicallyInactive,
        updatedAtEpochMs = runCatching { Instant.parse(updatedAt).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis()),
        synced = synced,
    )

    companion object {
        fun from(entity: HealthChecklistEntity, userId: String) = HealthChecklistRow(
            userId = userId,
            smoking = entity.smoking,
            heartCondition = entity.heartCondition,
            hypertension = entity.hypertension,
            diabetes = entity.diabetes,
            sleepDisorder = entity.sleepDisorder,
            anxietyHistory = entity.anxietyHistory,
            highCaffeineUse = entity.highCaffeineUse,
            physicallyInactive = entity.physicallyInactive,
            updatedAt = entity.updatedAtEpochMs.toIso(),
        )
    }
}

@Serializable
data class AlertEventRow(
    @SerialName("user_id") val userId: String,
    @SerialName("fired_at") val firedAt: String,
    val reason: String,
    @SerialName("high_count_in_window") val highCountInWindow: Int,
    @SerialName("window_size") val windowSize: Int,
    @SerialName("model_version") val modelVersion: String,
    val dismissed: Boolean,
) {
    companion object {
        fun from(entity: AlertEventEntity, userId: String) = AlertEventRow(
            userId = userId,
            firedAt = entity.firedAtEpochMs.toIso(),
            reason = entity.reason,
            highCountInWindow = entity.highCountInWindow,
            windowSize = entity.windowSize,
            modelVersion = entity.modelVersion,
            dismissed = entity.dismissed,
        )
    }
}

@Serializable
data class StressFeedbackRow(
    @SerialName("user_id") val userId: String,
    @SerialName("prompt_source") val promptSource: String,
    @SerialName("alert_fired_at") val alertFiredAt: String,
    @SerialName("prediction_recorded_at") val predictionRecordedAt: String,
    @SerialName("responded_at") val respondedAt: String,
    @SerialName("predicted_label") val predictedLabel: String,
    @SerialName("predicted_class_index") val predictedClassIndex: Int,
    val confidence: Float,
    val probabilities: List<Float>,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("heart_rate") val heartRate: Int,
    @SerialName("daily_steps") val dailySteps: Int,
    @SerialName("activity_level") val activityLevel: Int,
    @SerialName("sleep_hours") val sleepHours: Float,
    @SerialName("out_of_training_range") val outOfTrainingRange: Boolean,
    @SerialName("profile_age") val profileAge: Int,
    @SerialName("profile_gender") val profileGender: String,
    @SerialName("profile_occupation") val profileOccupation: String,
    @SerialName("profile_bmi") val profileBmi: String,
    @SerialName("confirmed_stressed") val confirmedStressed: Boolean,
    val severity: Int?,
) {
    companion object {
        fun from(entity: StressFeedbackEntity, userId: String): StressFeedbackRow {
            val respondedAt = requireNotNull(entity.respondedAtEpochMs)
            val confirmed = requireNotNull(entity.confirmedStressed)
            return StressFeedbackRow(
                userId = userId,
                promptSource = entity.promptSource,
                alertFiredAt = entity.alertFiredAtEpochMs.toIso(),
                predictionRecordedAt = entity.predictionRecordedAtEpochMs.toIso(),
                respondedAt = respondedAt.toIso(),
                predictedLabel = entity.predictedLabel,
                predictedClassIndex = entity.predictedClassIndex,
                confidence = entity.confidence,
                probabilities = entity.probabilities,
                modelVersion = entity.modelVersion,
                heartRate = entity.heartRate,
                dailySteps = entity.dailySteps,
                activityLevel = entity.activityLevel,
                sleepHours = entity.sleepHours,
                outOfTrainingRange = entity.outOfTrainingRange,
                profileAge = entity.profileAge,
                profileGender = entity.profileGender,
                profileOccupation = entity.profileOccupation,
                profileBmi = entity.profileBmi,
                confirmedStressed = confirmed,
                severity = entity.severity,
        )
    }

}
}
