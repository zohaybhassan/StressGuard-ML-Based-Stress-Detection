package com.example.stressguard.data

import android.os.SystemClock
import android.util.Log
import com.example.stressguard.data.local.LatencyMetricDao
import com.example.stressguard.data.local.LatencyMetricEntity

/**
 * Timings for one pass down the real-time path.
 *
 * Stages follow plan §12: arrival, preprocessing, inference, UI update, alert. The clock
 * starts at the watch message rather than when inference begins, so decryption, parsing and
 * feature building are all inside the measurement.
 *
 * **What this can and cannot measure.** Everything here is measured on the phone. Watch-to-
 * phone transmission is not included and cannot be, because timing it would require the two
 * devices' clocks to be synchronised. Plan §12 calls the first target "BLE receive to
 * prediction"; what is actually measured is *message arrival on the phone* to prediction. The
 * report should say so rather than let the figure imply end-to-end coverage.
 *
 * The clock is injectable so the arithmetic can be unit-tested: `SystemClock` is stubbed on
 * the JVM and returns 0 for every call.
 */
class LatencySample(
    private val receivedAtElapsedMs: Long,
    private val receivedAtEpochMs: Long,
    private val coldStart: Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var preprocessingDoneAt: Long? = null
    private var inferenceDoneAt: Long? = null
    private var uiUpdatedAt: Long? = null
    private var alertFiredAt: Long? = null

    /** Feature vector ready. */
    fun markPreprocessed() = apply { preprocessingDoneAt = now() }

    /** Probabilities available. */
    fun markInferred() = apply { inferenceDoneAt = now() }

    /** Dashboard reflects the new prediction. */
    fun markUiUpdated() = apply { uiUpdatedAt = now() }

    /** Haptic actually fired. Left unset when the smoothing rule did not trigger. */
    fun markAlertFired() = apply { alertFiredAt = now() }

    /**
     * Null until inference has been marked — a sample that never reached a prediction has no
     * latency worth recording, and storing a partial one would drag the averages down for a
     * reason unrelated to speed.
     */
    fun build(): LatencyMetricEntity? {
        val preprocessed = preprocessingDoneAt ?: return null
        val inferred = inferenceDoneAt ?: return null
        val lastStage = alertFiredAt ?: uiUpdatedAt ?: inferred

        return LatencyMetricEntity(
            recordedAtEpochMs = receivedAtEpochMs,
            preprocessingMs = preprocessed - receivedAtElapsedMs,
            inferenceMs = inferred - preprocessed,
            uiUpdateMs = (uiUpdatedAt ?: inferred) - inferred,
            receiveToPredictionMs = inferred - receivedAtElapsedMs,
            predictionToAlertMs = alertFiredAt?.let { it - inferred },
            totalMs = lastStage - receivedAtElapsedMs,
            coldStart = coldStart,
        )
    }
}

/** Rolling figures for the dashboard and the report. Cold starts excluded from the averages. */
data class LatencySummary(
    val steadyStateSamples: Int,
    val latestReceiveToPredictionMs: Long?,
    val latestTotalMs: Long?,
    val averageReceiveToPredictionMs: Double?,
    val averageTotalMs: Double?,
    val averagePredictionToAlertMs: Double?,
) {
    /** Plan §12: receive-to-prediction under 1 s, prediction-to-alert under 300 ms, total under 1.5 s. */
    val meetsReceiveToPredictionTarget: Boolean?
        get() = averageReceiveToPredictionMs?.let { it < 1_000 }

    val meetsTotalTarget: Boolean?
        get() = averageTotalMs?.let { it < 1_500 }

    val meetsAlertTarget: Boolean?
        get() = averagePredictionToAlertMs?.let { it < 300 }

    companion object {
        val EMPTY = LatencySummary(0, null, null, null, null, null)
    }
}

/** Persists samples and computes the rolling summary. */
class LatencyTracker(private val dao: LatencyMetricDao) {

    suspend fun record(sample: LatencySample) {
        val entity = sample.build() ?: return
        dao.insert(entity)
        Log.d(
            TAG,
            "receive->prediction ${entity.receiveToPredictionMs} ms " +
                "(pre ${entity.preprocessingMs}, infer ${entity.inferenceMs}), " +
                "total ${entity.totalMs} ms" +
                (if (entity.coldStart) " [cold start, includes model load]" else "") +
                (entity.predictionToAlertMs?.let { ", prediction->alert $it ms" } ?: "")
        )
    }

    suspend fun summary(): LatencySummary {
        val latest = dao.latest(1).firstOrNull()
        return LatencySummary(
            steadyStateSamples = dao.steadyStateSampleCount(),
            latestReceiveToPredictionMs = latest?.receiveToPredictionMs,
            latestTotalMs = latest?.totalMs,
            averageReceiveToPredictionMs = dao.averageReceiveToPredictionMs(),
            averageTotalMs = dao.averageTotalMs(),
            averagePredictionToAlertMs = dao.averagePredictionToAlertMs(),
        )
    }

    companion object {
        const val TAG = "STRESS_LATENCY"
    }
}
