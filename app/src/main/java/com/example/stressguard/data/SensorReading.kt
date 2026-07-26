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
     * How old the sample already was when the watch sent it, as measured on the watch.
     *
     * Background collection uses Health Services passive monitoring, which batches deliveries to
     * save power, so a sample can be minutes old on arrival. Arrival time alone would date all of
     * them "now" and quietly present stale vitals as current — the same conflation that made a
     * frozen heart rate look live.
     *
     * A duration rather than a timestamp on purpose: the watch and the phone do not share a
     * clock, but an elapsed time measured on either device means the same thing on both. Zero
     * when the watch did not report it.
     */
    val sampleAgeMs: Long = 0L,
    /**
     * True when a value is physiologically believable but outside the range the model was
     * trained on. The trees clamp to their outermost leaf there, so the prediction is an
     * extrapolation rather than an interpolation. Recorded rather than hidden: live wearable
     * heart rate exceeds the training maximum during ordinary activity, and a report that
     * quotes holdout accuracy should say how often it was predicting outside that range.
     */
    val outOfTrainingRange: Boolean,
) {
    /** When the sample was actually taken, as opposed to when it reached the phone. */
    val measuredAtEpochMs: Long get() = receivedAtEpochMs - sampleAgeMs

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

        /**
         * Past this, a sample describes a moment that has gone. Matches the staleness threshold
         * the two UIs use, so "current" means one thing across the app.
         */
        const val STALE_SAMPLE_MS = 30_000L

        /** Returns null when the sample is not believable, so callers cannot skip validation. */
        fun from(
            heartRate: Int,
            dailySteps: Int,
            receivedAtElapsedMs: Long,
            receivedAtEpochMs: Long,
            sampleAgeMs: Long = 0L,
        ): SensorReading? {
            if (heartRate !in PLAUSIBLE_HEART_RATE) return null
            if (dailySteps !in PLAUSIBLE_STEPS) return null

            return SensorReading(
                heartRate = heartRate,
                dailySteps = dailySteps,
                receivedAtElapsedMs = receivedAtElapsedMs,
                receivedAtEpochMs = receivedAtEpochMs,
                // A negative age would mean the watch's clock ran ahead of its own sample, which
                // is a bug rather than a measurement; treated as fresh instead of propagating a
                // duration that would make the reading appear to come from the future.
                sampleAgeMs = sampleAgeMs.coerceAtLeast(0L),
                outOfTrainingRange = heartRate !in TRAINED_HEART_RATE ||
                    dailySteps !in TRAINED_STEPS,
            )
        }

        /**
         * Parses the watch payload, `"<heartRate>|<dailySteps>"` with an optional third field
         * giving the sample's age in milliseconds as measured on the watch.
         *
         * The age is optional so that a watch build without it still parses; a missing age is
         * read as zero, which is what the foreground path used to imply anyway.
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
            // An unparseable age falls back to zero rather than dropping an otherwise good
            // reading: the vitals are the payload, the age is metadata about them.
            val sampleAgeMs = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L

            return from(heartRate, dailySteps, receivedAtElapsedMs, receivedAtEpochMs, sampleAgeMs)
        }
    }
}
