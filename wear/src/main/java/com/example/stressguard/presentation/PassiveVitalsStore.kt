package com.example.stressguard.presentation

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The small amount of state that has to outlive the process.
 *
 * [PassiveVitalsService] is bound on demand and its instance is discarded between batches, so an
 * in-memory field is not merely a bad idea here — it is guaranteed to be lost. Two things need
 * to survive:
 *
 *  - the **daily step total**, because a batch may carry heart rate with no steps, and a reading
 *    needs both;
 *  - the **last send time**, because the throttle is otherwise reset on every delivery and stops
 *    throttling anything.
 */
class PassiveVitalsStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Notes a step count for the day [atEpochMs] falls in, keeping whichever figure is higher.
     *
     * Two sources write here and they are not equally trustworthy. `DataType.STEPS_DAILY` is the
     * platform's own count since midnight. [DailyStepCounter] derives one by subtracting a
     * baseline it stored itself, and that baseline is reset by a reinstall or a reboot — after
     * which it reports roughly zero until the wearer walks again.
     *
     * A plain setter let the weaker source overwrite the stronger one. The logs caught it exactly:
     * a passive batch recorded 4010 steps, and seconds later the foreground path wrote 0 over it,
     * so the phone was told the wearer had taken no steps all day while the watch face said 4010.
     * Taking the maximum makes the order of arrival stop mattering.
     *
     * Scoped to a day because the maximum alone would never fall: at midnight the platform's count
     * resets to zero and yesterday's total would otherwise stand in for today's forever.
     */
    fun recordSteps(steps: Int, atEpochMs: Long) {
        if (steps < 0) return
        val today = dateKey(atEpochMs)
        val best = resolveSteps(
            storedDate = prefs.getString(KEY_STEPS_DATE, null),
            storedSteps = prefs.getInt(KEY_STEPS, 0),
            incomingDate = today,
            incomingSteps = steps,
        )

        prefs.edit()
            .putString(KEY_STEPS_DATE, today)
            .putInt(KEY_STEPS, best)
            .apply()
    }

    /** Steps taken today, or 0 if nothing has been recorded for today yet. */
    fun stepsToday(atEpochMs: Long): Int =
        if (prefs.getString(KEY_STEPS_DATE, null) == dateKey(atEpochMs)) {
            prefs.getInt(KEY_STEPS, 0)
        } else {
            0
        }

    /** Whether a background registration is believed to be in place. */
    var registered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    /**
     * Returns true if a send is allowed now, recording the time if so.
     *
     * Batches normally arrive minutes apart, so this rarely refuses. It exists for the case that
     * does burst: `flush` delivers everything buffered at once, and a device waking from a long
     * idle period can produce several deliveries in quick succession.
     */
    fun claimSendSlot(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong(KEY_LAST_SENT, 0L)

        // A reboot resets elapsedRealtime, so a stored value in the future is stale rather than
        // recent, and must not lock sending out until the clock catches up.
        val elapsed = now - last
        if (last != 0L && elapsed in 0 until MIN_SEND_INTERVAL_MS) return false

        prefs.edit().putLong(KEY_LAST_SENT, now).apply()
        return true
    }

    companion object {
        private const val NAME = "passive_vitals"
        private const val KEY_STEPS = "daily_steps"
        private const val KEY_STEPS_DATE = "daily_steps_date"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_LAST_SENT = "last_sent_elapsed_ms"

        /** `yyyy-MM-dd` in local time, so a day turns over at the wearer's midnight. */
        private fun dateKey(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))

        /**
         * The figure to keep, given what is stored and what has just arrived.
         *
         * Pure and separated from SharedPreferences so the rule that broke can be tested. Within
         * a day the higher number wins, because the two sources disagree in one direction only:
         * `DailyStepCounter` under-reports after losing its baseline, and never over-reports.
         * Across a day boundary the incoming value wins outright, since the platform's count has
         * reset to zero and yesterday's total would otherwise stand in for today's forever.
         */
        fun resolveSteps(
            storedDate: String?,
            storedSteps: Int,
            incomingDate: String,
            incomingSteps: Int,
        ): Int = if (storedDate == incomingDate) maxOf(storedSteps, incomingSteps) else incomingSteps

        /** Floor on transmissions; each one costs the phone an inference and a database write. */
        const val MIN_SEND_INTERVAL_MS = 5_000L
    }
}
