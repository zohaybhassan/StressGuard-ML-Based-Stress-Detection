package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Latency arithmetic.
 *
 * The clock is injected because `SystemClock.elapsedRealtime` is stubbed on the JVM and returns
 * 0 for every call, which would make every duration zero and every assertion here vacuous.
 *
 * These numbers end up quoted in the report, so the stage boundaries need to be exactly right:
 * an off-by-one-stage error would silently attribute preprocessing cost to inference.
 */
class LatencySampleTest {

    private val receivedAtElapsed = 10_000L
    private val receivedAtEpoch = 1_700_000_000_000L

    /** Returns successive fake clock values, so each mark lands at a known time. */
    private fun clockOf(vararg values: Long): () -> Long {
        val iterator = values.iterator()
        return { iterator.next() }
    }

    @Test
    fun stagesAreAttributedToTheRightIntervals() {
        // preprocessed at +40, inferred at +150, ui at +170
        val sample = LatencySample(
            receivedAtElapsedMs = receivedAtElapsed,
            receivedAtEpochMs = receivedAtEpoch,
            coldStart = false,
            now = clockOf(10_040, 10_150, 10_170),
        )

        val metric = sample.markPreprocessed().markInferred().markUiUpdated().build()

        assertNotNull(metric)
        assertEquals("arrival to feature vector", 40L, metric!!.preprocessingMs)
        assertEquals("feature vector to probabilities", 110L, metric.inferenceMs)
        assertEquals("probabilities to rendered", 20L, metric.uiUpdateMs)
        assertEquals("arrival to prediction", 150L, metric.receiveToPredictionMs)
        assertEquals("arrival to last stage", 170L, metric.totalMs)
        assertNull("no alert fired", metric.predictionToAlertMs)
    }

    @Test
    fun alertTimingIsMeasuredFromThePrediction() {
        val sample = LatencySample(
            receivedAtElapsedMs = receivedAtElapsed,
            receivedAtEpochMs = receivedAtEpoch,
            coldStart = false,
            now = clockOf(10_040, 10_150, 10_170, 10_250),
        )

        val metric = sample.markPreprocessed().markInferred().markUiUpdated().markAlertFired().build()

        assertNotNull(metric)
        assertEquals("prediction to haptic, not arrival to haptic", 100L, metric!!.predictionToAlertMs)
        assertEquals("total now runs to the alert", 250L, metric.totalMs)
    }

    /**
     * A sample that never reached a prediction has no latency worth recording, and storing a
     * partial one would drag the averages down for a reason unrelated to speed.
     */
    @Test
    fun anIncompleteSampleProducesNothing() {
        val base = { LatencySample(receivedAtElapsed, receivedAtEpoch, false, clockOf(10_040, 10_150)) }

        assertNull("nothing marked", base().build())
        assertNull("preprocessing only", base().markPreprocessed().build())
    }

    /** UI update is optional: inference completing is enough to record the path so far. */
    @Test
    fun uiUpdateIsOptional() {
        val sample = LatencySample(
            receivedAtElapsedMs = receivedAtElapsed,
            receivedAtEpochMs = receivedAtEpoch,
            coldStart = false,
            now = clockOf(10_040, 10_150),
        )

        val metric = sample.markPreprocessed().markInferred().build()

        assertNotNull(metric)
        assertEquals(0L, metric!!.uiUpdateMs)
        assertEquals("total falls back to the prediction", 150L, metric.totalMs)
    }

    @Test
    fun coldStartIsCarriedThroughToTheStoredRow() {
        val sample = LatencySample(
            receivedAtElapsedMs = receivedAtElapsed,
            receivedAtEpochMs = receivedAtEpoch,
            coldStart = true,
            now = clockOf(10_040, 12_500),
        )

        val metric = sample.markPreprocessed().markInferred().build()

        assertTrue(metric!!.coldStart)
        assertEquals("model loading shows up as inference time", 2_460L, metric.inferenceMs)
    }

    @Test
    fun wallClockTimeIsPreservedForOrdering() {
        val sample = LatencySample(
            receivedAtElapsedMs = receivedAtElapsed,
            receivedAtEpochMs = receivedAtEpoch,
            coldStart = false,
            now = clockOf(10_040, 10_150),
        )

        assertEquals(receivedAtEpoch, sample.markPreprocessed().markInferred().build()!!.recordedAtEpochMs)
    }
}

/** The plan's §12 targets, expressed as assertions so a regression is caught. */
class LatencySummaryTest {

    @Test
    fun targetsFollowPlanThresholds() {
        val comfortable = LatencySummary(
            steadyStateSamples = 30,
            latestReceiveToPredictionMs = 120,
            latestTotalMs = 150,
            averageReceiveToPredictionMs = 140.0,
            averageTotalMs = 180.0,
            averagePredictionToAlertMs = 60.0,
        )

        assertEquals(true, comfortable.meetsReceiveToPredictionTarget)
        assertEquals(true, comfortable.meetsTotalTarget)
        assertEquals(true, comfortable.meetsAlertTarget)
    }

    @Test
    fun breachingATargetIsReported() {
        val slow = LatencySummary(
            steadyStateSamples = 30,
            latestReceiveToPredictionMs = 1_400,
            latestTotalMs = 2_000,
            averageReceiveToPredictionMs = 1_200.0,
            averageTotalMs = 1_900.0,
            averagePredictionToAlertMs = 450.0,
        )

        assertEquals(false, slow.meetsReceiveToPredictionTarget)
        assertEquals(false, slow.meetsTotalTarget)
        assertEquals(false, slow.meetsAlertTarget)
    }

    /** Null rather than false with no data: "unknown" and "too slow" are different claims. */
    @Test
    fun withNoSamplesTheTargetsAreUnknownNotFailed() {
        assertNull(LatencySummary.EMPTY.meetsReceiveToPredictionTarget)
        assertNull(LatencySummary.EMPTY.meetsTotalTarget)
        assertNull(LatencySummary.EMPTY.meetsAlertTarget)
        assertEquals(0, LatencySummary.EMPTY.steadyStateSamples)
    }
}
