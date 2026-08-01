package com.example.stressguard.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * One stored prediction.
 *
 * The sensor values are denormalised onto the row rather than kept in a separate snapshots
 * table. Plan §10 asks for "useful sensor snapshots, not every raw BLE packet", and a reading
 * is only interesting alongside what the model made of it — storing them together avoids a
 * join for every trend query and keeps orphaned readings from accumulating.
 *
 * [synced] exists from the start so the Phase 7 sync worker has a queue to drain. Adding it
 * later would mean a migration plus backfilling rows whose sync state is unknowable.
 */
@Entity(
    tableName = "stress_predictions",
    indices = [Index("recordedAtEpochMs"), Index("synced")],
)
data class StressPredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAtEpochMs: Long,
    val label: String,
    val classIndex: Int,
    val confidence: Float,
    /** Full distribution, so a later re-analysis is not limited to the argmax. */
    val probabilities: List<Float>,
    /** Which model build produced this. Plan §19 requires it on every prediction record. */
    val modelVersion: String,
    val heartRate: Int,
    /** Steps since midnight, as the watch measured them. */
    val dailySteps: Int,
    /**
     * The step figure actually given to the model, which differs from [dailySteps] whenever the
     * day is still young. Both are stored so the history can explain its own predictions; see
     * `StepHistory`.
     */
    val activityLevel: Int = 0,
    val sleepHours: Float,
    /** The values given to the model fell outside its training ranges, so it extrapolated. */
    val outOfTrainingRange: Boolean,
    val synced: Boolean = false,
)

/**
 * Timings for one pass down the real-time path.
 *
 * All durations are derived from `SystemClock.elapsedRealtime`, so they are unaffected by wall
 * clock changes. [recordedAtEpochMs] is separate and only used for ordering and display.
 *
 * [coldStart] separates the first inference, which loads roughly 13.8 MB of ONNX graphs, from
 * steady state. Averaging the two together would misrepresent the result in whichever
 * direction the sample happened to be dominated by.
 */
@Entity(
    tableName = "latency_metrics",
    indices = [Index("recordedAtEpochMs"), Index("synced")],
)
data class LatencyMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAtEpochMs: Long,
    /** Message arrival to the feature vector being ready. */
    val preprocessingMs: Long,
    /** Feature vector in, probabilities out. Includes model load when [coldStart]. */
    val inferenceMs: Long,
    /** Prediction available to the dashboard showing it. */
    val uiUpdateMs: Long,
    /** Arrival to prediction available. Plan §12 targets under 1000 ms. */
    val receiveToPredictionMs: Long,
    /** Prediction to haptic fired. Null when no alert fired. Plan §12 targets under 300 ms. */
    val predictionToAlertMs: Long?,
    /** Arrival to the last stage reached. Plan §12 targets under 1500 ms. */
    val totalMs: Long,
    val coldStart: Boolean,
    val synced: Boolean = false,
)

/**
 * How many steps were taken on a given day.
 *
 * Exists because the model's "Daily Steps" feature means a person's habitual full-day activity
 * level — 1000 to 16036 in the training data, where every row is a person rather than a moment.
 * The watch reports steps *since midnight*, which is necessarily 0 at midnight and below the
 * trained minimum for the first hours of every day. Feeding that straight in put every morning
 * prediction outside the trained range, where the trees clamp to their outermost leaf and stop
 * responding to heart rate at all.
 *
 * Keeping one row per day lets [com.example.stressguard.data.StepHistory] answer the question the
 * model is actually asking. Not synced: it is an input to inference, not a record of it, and it is
 * derivable from the prediction history already being uploaded.
 */
@Entity(tableName = "daily_step_totals")
data class DailyStepTotalEntity(
    /** `yyyy-MM-dd` in local time, so a day rolls over at the user's midnight rather than UTC's. */
    @PrimaryKey val date: String,
    /** Highest count seen for the day. The watch's counter only climbs until it resets. */
    val steps: Int,
    val updatedAtEpochMs: Long,
)

/** A fired alert, retained so cooldown survives a restart and so history can be shown. */
@Entity(
    tableName = "alert_events",
    indices = [Index("firedAtEpochMs"), Index("synced")],
)
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val firedAtEpochMs: Long,
    /** Human-readable trigger, e.g. "3 of the last 5 readings were stressed". */
    val reason: String,
    val highCountInWindow: Int,
    val windowSize: Int,
    val modelVersion: String,
    val dismissed: Boolean = false,
    val synced: Boolean = false,
)

/**
 * Human ground truth captured after a sustained-stress alert.
 *
 * The model and profile inputs are snapshotted when the alert fires. Keeping them on this row is
 * intentional: a response may arrive hours later, after the user has walked more, slept again, or
 * edited their profile. Joining against "latest" values at response time would create mislabeled
 * training examples.
 */
@Entity(
    tableName = "stress_feedback",
    indices = [Index(value = ["alertFiredAtEpochMs"], unique = true), Index("synced")],
)
data class StressFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertEventId: Long,
    val promptSource: String = "high_stress_alert",
    val alertFiredAtEpochMs: Long,
    val predictionRecordedAtEpochMs: Long,
    val predictedLabel: String,
    val predictedClassIndex: Int,
    val confidence: Float,
    val probabilities: List<Float>,
    val modelVersion: String,
    val heartRate: Int,
    val dailySteps: Int,
    val activityLevel: Int,
    val sleepHours: Float,
    val outOfTrainingRange: Boolean,
    val profileAge: Int,
    val profileGender: String,
    val profileOccupation: String,
    val profileBmi: String,
    /** Null until the user answers the alert check-in. */
    val confirmedStressed: Boolean? = null,
    /** Original dataset scale. Required for a confirmed stress response, otherwise null. */
    val severity: Int? = null,
    val respondedAtEpochMs: Long? = null,
    val synced: Boolean = false,
)

/**
 * The user's self-reported health risk factors, as plan §7 scores them.
 *
 * One row, always id [SINGLETON_ID]. The table holds the *current* answers for whoever is signed
 * in on this device rather than a history of them: the risk score asks "does this user smoke",
 * not "when did they say so". A fixed primary key makes that structural, so a second save
 * replaces the first instead of accumulating rows the score would then have to disambiguate.
 *
 * Self-reported, and stored as such. Nothing here is measured, which is exactly why plan §7 uses
 * it to recommend a checkup rather than to conclude anything.
 *
 * [synced] mirrors the other tables so the existing worker can drain it.
 */
@Entity(tableName = "health_checklists")
data class HealthChecklistEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val smoking: Boolean = false,
    val heartCondition: Boolean = false,
    val hypertension: Boolean = false,
    val diabetes: Boolean = false,
    val sleepDisorder: Boolean = false,
    val anxietyHistory: Boolean = false,
    val highCaffeineUse: Boolean = false,
    val physicallyInactive: Boolean = false,
    val updatedAtEpochMs: Long,
    val synced: Boolean = false,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * Room stores a probability vector as a comma-separated string.
 *
 * Deliberately not JSON: the value is always a flat list of floats, and a plain join keeps the
 * column readable in a database inspector, which matters when demonstrating stored history.
 */
class Converters {
    @TypeConverter
    fun floatListToString(values: List<Float>?): String =
        values?.joinToString(",").orEmpty()

    @TypeConverter
    fun stringToFloatList(value: String?): List<Float> =
        if (value.isNullOrBlank()) emptyList()
        else value.split(',').mapNotNull { it.trim().toFloatOrNull() }
}
