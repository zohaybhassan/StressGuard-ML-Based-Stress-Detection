package com.example.stressguard.data

import com.example.stressguard.data.local.DailyStepTotalDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Supplies the "Daily Steps" feature the model was trained on.
 *
 * The model's column describes a person's **habitual full-day activity level**: 1000 to 16036 in
 * `ml_engine/data/sleep_health_dataset.csv`, where every row is a person rather than a moment. The
 * watch reports steps *since midnight*, which is a different quantity that happens to share a name
 * — it is 0 at midnight and below the trained minimum for the first hours of every day.
 *
 * Feeding the partial count straight in put every morning prediction outside the trained range.
 * Tree ensembles do not extrapolate; they clamp to the outermost leaf, so the prediction stopped
 * responding to heart rate. A full night's run produced 99 predictions, all "stressed", all flagged
 * as extrapolation, on step counts of 0 to 458. It is the same class of defect as the z-scored
 * training data fixed earlier: the right number for the wrong quantity.
 *
 * The rule is `max(today so far, most recent complete day)`. It is in range once a single day has
 * elapsed, still rises when someone is genuinely more active today than yesterday, and is
 * defensible in a sentence: the model wants this user's daily activity level, so supply the best
 * estimate available rather than one that is structurally too low every morning.
 */
class StepHistory(private val dao: DailyStepTotalDao) {

    /** Notes the step count for the day [atEpochMs] falls in, keeping the highest seen. */
    suspend fun record(dailySteps: Int, atEpochMs: Long) {
        if (dailySteps < 0) return
        dao.upsertMax(dateKey(atEpochMs), dailySteps, atEpochMs)
    }

    /**
     * The figure to give the model.
     *
     * Returns [todaySteps] unchanged when no earlier day is stored. That is honest rather than
     * convenient: on the first day the user's activity level genuinely is unknown, and the
     * prediction is correctly flagged as an extrapolation until a full day exists.
     */
    suspend fun activityLevel(todaySteps: Int, nowEpochMs: Long): Int {
        val previous = dao.mostRecentBefore(dateKey(nowEpochMs))?.steps ?: return todaySteps
        return maxOf(todaySteps, previous)
    }

    companion object {
        /**
         * `yyyy-MM-dd` in the device's own zone, so a day rolls over at the user's midnight.
         *
         * `SimpleDateFormat` is not thread-safe, so a new one is built per call rather than
         * shared. This runs a few times an hour at most.
         */
        fun dateKey(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { this.timeZone = timeZone }
                .format(Date(epochMs))
    }
}
