package com.example.stressguard.data

/**
 * One sensor sample received from the watch, timestamped on arrival.
 *
 * Two clocks, deliberately:
 *  - [receivedAtElapsedMs] is `SystemClock.elapsedRealtime`, monotonic and unaffected by the
 *    user or the network changing the wall clock. Every latency measurement derives from it,
 *    so a clock adjustment mid-sample cannot produce a negative or absurd duration.
 *  - [receivedAtEpochMs] is wall-clock, for storing and displaying *when* a reading happened.
 *
 * Construct through [SensorReading.from], which rejects impossible values rather than letting
 * them reach the model.
 */
data class SensorReading(
    val heartRate: Int,
    val dailySteps: Int,
    val receivedAtElapsedMs: Long,
    val receivedAtEpochMs: Long,
    /**
     * True when a value is physiologically believable but outside the range the model was
     * trained on. The trees clamp to their outermost leaf there, so the prediction is an
     * extrapolation rather than an interpolation. Recorded rather than hidden: live wearable
     * heart rate exceeds the training maximum during ordinary activity, and a report that
     * quotes holdout accuracy should say how often it was predicting outside that range.
     */
    val outOfTrainingRange: Boolean,
) {
    companion object {
        // Physiologically possible. Outside this the reading is a sensor fault or a parsing
        // error, not a person, so it is discarded.
        private val PLAUSIBLE_HEART_RATE = 30..220
        private val PLAUSIBLE_STEPS = 0..100_000

        // Covered by ml_engine/data/sleep_health_dataset.csv, the data the model was fit on.
        // Kept in sync by StressFeatureBuilderTest via the shipped manifest's feature list;
        // the manifest does not record per-feature ranges, so these are asserted here.
        val TRAINED_HEART_RATE = 43..109
        val TRAINED_STEPS = 1000..16036
        val TRAINED_SLEEP_HOURS = 5.1f..10.0f

        /** Returns null when the sample is not believable, so callers cannot skip validation. */
        fun from(
            heartRate: Int,
            dailySteps: Int,
            receivedAtElapsedMs: Long,
            receivedAtEpochMs: Long,
        ): SensorReading? {
            if (heartRate !in PLAUSIBLE_HEART_RATE) return null
            if (dailySteps !in PLAUSIBLE_STEPS) return null

            return SensorReading(
                heartRate = heartRate,
                dailySteps = dailySteps,
                receivedAtElapsedMs = receivedAtElapsedMs,
                receivedAtEpochMs = receivedAtEpochMs,
                outOfTrainingRange = heartRate !in TRAINED_HEART_RATE ||
                    dailySteps !in TRAINED_STEPS,
            )
        }

        /**
         * Parses the watch payload, which is `"<heartRate>|<dailySteps>"`.
         *
         * Returns null on anything malformed. The watch and phone are versioned together, but
         * a truncated or partially-decrypted message should be dropped rather than guessed at.
         */
        fun parse(
            payload: String,
            receivedAtElapsedMs: Long,
            receivedAtEpochMs: Long,
        ): SensorReading? {
            val parts = payload.split('|')
            if (parts.size < 2) return null

            val heartRate = parts[0].trim().toIntOrNull() ?: return null
            val dailySteps = parts[1].trim().toIntOrNull() ?: return null

            return from(heartRate, dailySteps, receivedAtElapsedMs, receivedAtEpochMs)
        }
    }
}
