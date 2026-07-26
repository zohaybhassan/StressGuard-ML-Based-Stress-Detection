package com.example.stressguard.presentation

import android.content.Context
import android.os.SystemClock

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

    /** Steps since midnight, as last reported by Health Services. */
    var dailySteps: Int
        get() = prefs.getInt(KEY_STEPS, 0)
        set(value) = prefs.edit().putInt(KEY_STEPS, value).apply()

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
        private const val KEY_REGISTERED = "registered"
        private const val KEY_LAST_SENT = "last_sent_elapsed_ms"

        /** Floor on transmissions; each one costs the phone an inference and a database write. */
        const val MIN_SEND_INTERVAL_MS = 5_000L
    }
}
