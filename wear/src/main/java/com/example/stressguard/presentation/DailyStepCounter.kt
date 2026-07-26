package com.example.stressguard.presentation

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * Converts the hardware step counter into steps taken *today*.
 *
 * `Sensor.TYPE_STEP_COUNTER` reports a cumulative total since the watch last booted, which is
 * not what the model consumes: its `Daily Steps` feature was trained on 1000-16036, so a watch
 * up for a week would report a number far outside that range and the trees would simply clamp
 * to their outermost leaf.
 *
 * The conversion keeps a baseline — the counter's value at the start of today — in
 * SharedPreferences, and reports the difference.
 *
 * Two cases the baseline has to survive:
 *  - **A new day.** The baseline resets to the current counter value, so today starts at zero.
 *  - **A reboot.** The hardware counter restarts at zero, so a reading *below* the stored
 *    baseline means the device restarted. Treating that as a negative step count would fail
 *    validation on the phone, so the baseline resets instead.
 */
class DailyStepCounter(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** @param sinceBootCount the raw cumulative value from TYPE_STEP_COUNTER. */
    fun today(sinceBootCount: Long): Int {
        val today = dayKey()
        val storedDay = prefs.getString(KEY_DAY, null)
        var baseline = prefs.getLong(KEY_BASELINE, -1L)

        val newDay = storedDay != today
        val rebooted = baseline >= 0 && sinceBootCount < baseline

        if (baseline < 0 || newDay || rebooted) {
            // On a reboot the counter is already counting today's steps from zero, so the
            // baseline is zero rather than the current value -- otherwise steps taken between
            // boot and this reading would be discarded.
            baseline = if (rebooted) 0L else sinceBootCount
            prefs.edit()
                .putLong(KEY_BASELINE, baseline)
                .putString(KEY_DAY, today)
                .apply()
            Log.i(
                TAG,
                "step baseline set to $baseline (" +
                    (if (rebooted) "device rebooted" else if (newDay) "new day" else "first run") + ")"
            )
        }

        return (sinceBootCount - baseline).coerceAtLeast(0L).toInt()
    }

    /** Local calendar day, so "today" follows the wearer's timezone rather than UTC. */
    private fun dayKey(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    companion object {
        private const val TAG = "WEAR_VITALS"
        private const val PREFS = "StressGuardSteps"
        private const val KEY_BASELINE = "day_start_counter"
        private const val KEY_DAY = "day_key"
    }
}
