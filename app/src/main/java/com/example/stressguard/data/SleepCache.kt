package com.example.stressguard.data

import android.content.Context

/**
 * The last sleep figure read from Health Connect, so background inference has one to use.
 *
 * Health Connect is read by the dashboard, but predictions now happen without it — vitals arrive
 * from the watch while the app is closed. Reading Health Connect from the receiver instead would
 * mean a permission check and a query on the critical path of every reading, for a value that
 * changes once a day.
 *
 * Sleep is one of the model's three live inputs, so substituting the training-set mean on every
 * background prediction would flatten a third of the signal. Caching the real figure keeps the
 * foreground and background paths predicting from the same inputs.
 */
class SleepCache(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Stores a figure just read from Health Connect. */
    fun put(hours: Float) {
        prefs.edit()
            .putFloat(KEY_HOURS, hours)
            .putLong(KEY_READ_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * The cached figure, or null if there is none or it has aged out.
     *
     * Expiry matters: a value from three days ago is not this user's last night, and quietly
     * feeding it to the model would be worse than the documented default, because it looks like
     * a real measurement in the stored history.
     */
    fun hours(now: Long = System.currentTimeMillis()): Float? {
        if (!prefs.contains(KEY_HOURS)) return null
        val readAt = prefs.getLong(KEY_READ_AT, 0L)
        // A backwards wall clock makes the age negative; treated as expired rather than trusted.
        val age = now - readAt
        if (age < 0 || age > MAX_AGE_MS) return null
        return prefs.getFloat(KEY_HOURS, 0f)
    }

    private companion object {
        const val NAME = "sleep_cache"
        const val KEY_HOURS = "hours"
        const val KEY_READ_AT = "read_at_epoch_ms"

        /**
         * Sleep is a nightly figure, so a cached one stays meaningful for most of a day. Beyond
         * that it describes a night the user has already had another one after.
         */
        const val MAX_AGE_MS = 18 * 60 * 60 * 1000L
    }
}
