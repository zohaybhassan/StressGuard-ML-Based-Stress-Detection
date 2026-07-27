package com.example.stressguard.data

import com.example.stressguard.data.local.StressPredictionEntity
import java.util.TimeZone

/**
 * One day of stored predictions, rolled up.
 *
 * Dates are `yyyy-MM-dd` in the device's own zone, matching [StepHistory.dateKey], so a day rolls
 * over at the user's midnight rather than UTC's. Getting that wrong would shift roughly a third of
 * an evening's readings into the following day for anyone west of Greenwich, and the risk score
 * counts *days*.
 */
data class DailyStressSummary(
    val date: String,
    val readings: Int,
    val highStressReadings: Int,
    val averageHeartRate: Int,
    val averageSleepHours: Float,
    val averageActivityLevel: Int,
) {
    /**
     * Whether this counts as a high-stress day for plan §7's "high stress on N days" rules.
     *
     * The bar is [StressAlertPolicy.THRESHOLD] readings, deliberately the same number the alert
     * rule uses. One high reading is not a high-stress day — heart rate spikes from climbing
     * stairs, and with passive collection a day holds tens of readings, so a single-reading rule
     * would mark almost every day high and the score would saturate for everyone.
     *
     * Reusing the alert's threshold also means the recommendation and the alerts agree about what
     * counted, which is what makes "you were alerted on four days this week" and the score
     * explainable as one story rather than two.
     */
    val isHighStressDay: Boolean
        get() = highStressReadings >= StressAlertPolicy.THRESHOLD
}

/**
 * Turns stored predictions into per-day summaries.
 *
 * Pure and synchronous: it takes rows rather than a DAO, so the rules can be tested exhaustively
 * without a database, in the same spirit as [StressAlertPolicy].
 *
 * Deliberately **not** a stored table. Plan §6 lists `daily_stress_summaries`, but a persisted
 * rollup here would duplicate data the app already holds and introduce a staleness bug for nothing:
 * local retention keeps 30 days and every window the score or the trends screen asks about is 14
 * days or less, so the source rows are always present. Every prediction is already uploaded, so
 * long-term server-side rollups remain possible without the app maintaining a second copy.
 */
object StressHistory {

    /**
     * @param predictions any set of stored rows; only their date matters, so order is irrelevant.
     * @param highStressClassIndex the most severe class, taken from the model manifest rather than
     *   hardcoded — the shipped bundle is binary but the three-level one is swappable.
     * @return one entry per day that has at least one reading, newest first. Days with no readings
     *   are absent rather than zero-filled: "the watch was not worn" and "the watch was worn and
     *   showed no stress" are different facts, and only the caller knows which it needs.
     */
    fun summarise(
        predictions: List<StressPredictionEntity>,
        highStressClassIndex: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<DailyStressSummary> =
        predictions
            .groupBy { StepHistory.dateKey(it.recordedAtEpochMs, timeZone) }
            .map { (date, rows) ->
                DailyStressSummary(
                    date = date,
                    readings = rows.size,
                    highStressReadings = rows.count { it.classIndex == highStressClassIndex },
                    averageHeartRate = rows.map { it.heartRate }.average().toInt(),
                    averageSleepHours = rows.map { it.sleepHours }.average().toFloat(),
                    averageActivityLevel = rows.map { it.activityLevel }.average().toInt(),
                )
            }
            .sortedByDescending { it.date }

    /** How many of [summaries] are high-stress days. The unit plan §7's rules are written in. */
    fun highStressDayCount(summaries: List<DailyStressSummary>): Int =
        summaries.count { it.isHighStressDay }
}
