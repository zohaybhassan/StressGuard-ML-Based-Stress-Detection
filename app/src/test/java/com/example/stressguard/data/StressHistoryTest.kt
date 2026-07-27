package com.example.stressguard.data

import com.example.stressguard.data.local.StressPredictionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The per-day rollup the risk score counts in.
 *
 * The interesting cases are all about *day boundaries*, because plan §7's rules are expressed in
 * days and a reading assigned to the wrong one changes the count directly.
 */
class StressHistoryTest {

    private val high = 1
    private val low = 0

    /** 2026-07-20T12:00:00Z, comfortably mid-day in every zone used below. */
    private val noonUtc = 1_784_548_800_000L

    private fun prediction(
        atEpochMs: Long,
        classIndex: Int = low,
        heartRate: Int = 70,
        sleepHours: Float = 7.5f,
        activityLevel: Int = 8000,
    ) = StressPredictionEntity(
        recordedAtEpochMs = atEpochMs,
        label = if (classIndex == high) "stressed" else "not_stressed",
        classIndex = classIndex,
        confidence = 0.9f,
        probabilities = listOf(0.1f, 0.9f),
        modelVersion = "Voting_top3_tuned/binary",
        heartRate = heartRate,
        dailySteps = activityLevel,
        activityLevel = activityLevel,
        sleepHours = sleepHours,
        outOfTrainingRange = false,
    )

    private fun summarise(
        predictions: List<StressPredictionEntity>,
        timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
    ) = StressHistory.summarise(predictions, highStressClassIndex = high, timeZone = timeZone)

    @Test
    fun groupsReadingsByDay() {
        val summaries = summarise(
            listOf(
                prediction(noonUtc),
                prediction(noonUtc + TimeUnit.HOURS.toMillis(1)),
                prediction(noonUtc + TimeUnit.DAYS.toMillis(1)),
            )
        )

        assertEquals(2, summaries.size)
        assertEquals(1, summaries.first().readings)  // newest first
        assertEquals(2, summaries.last().readings)
    }

    @Test
    fun newestDayComesFirst() {
        val summaries = summarise(
            listOf(
                prediction(noonUtc),
                prediction(noonUtc + TimeUnit.DAYS.toMillis(2)),
                prediction(noonUtc + TimeUnit.DAYS.toMillis(1)),
            )
        )

        assertEquals(listOf("2026-07-22", "2026-07-21", "2026-07-20"), summaries.map { it.date })
    }

    /**
     * Days are the user's own, not UTC's.
     *
     * A reading at 23:30 local in a zone behind UTC falls on the *next* UTC day. Grouping by UTC
     * would move a whole evening's readings into tomorrow, and plan §7 counts days.
     */
    @Test
    fun daysRollOverAtTheUsersMidnightNotUtcs() {
        // 2026-07-21T01:00:00Z is 2026-07-20 at 21:00 in New York.
        val justAfterUtcMidnight = noonUtc + TimeUnit.HOURS.toMillis(13)

        val utc = summarise(listOf(prediction(justAfterUtcMidnight)))
        val newYork = summarise(
            listOf(prediction(justAfterUtcMidnight)),
            timeZone = TimeZone.getTimeZone("America/New_York"),
        )

        assertEquals("2026-07-21", utc.single().date)
        assertEquals("2026-07-20", newYork.single().date)
    }

    /** One high reading is not a high-stress day; the bar is the alert rule's threshold. */
    @Test
    fun aDayNeedsThresholdHighReadingsToCount() {
        val belowThreshold = List(StressAlertPolicy.THRESHOLD - 1) { prediction(noonUtc, high) }
        val atThreshold = List(StressAlertPolicy.THRESHOLD) { prediction(noonUtc, high) }

        assertFalse(summarise(belowThreshold).single().isHighStressDay)
        assertTrue(summarise(atThreshold).single().isHighStressDay)
    }

    @Test
    fun countsHighStressDaysAcrossTheWindow() {
        val predictions = buildList {
            // Two days that clear the bar, one that does not.
            repeat(StressAlertPolicy.THRESHOLD) { add(prediction(noonUtc, high)) }
            repeat(StressAlertPolicy.THRESHOLD) {
                add(prediction(noonUtc + TimeUnit.DAYS.toMillis(1), high))
            }
            add(prediction(noonUtc + TimeUnit.DAYS.toMillis(2), high))
        }

        assertEquals(2, StressHistory.highStressDayCount(summarise(predictions)))
    }

    @Test
    fun averagesVitalsWithinADay() {
        val summary = summarise(
            listOf(
                prediction(noonUtc, heartRate = 60, sleepHours = 7.0f, activityLevel = 4000),
                prediction(noonUtc, heartRate = 80, sleepHours = 8.0f, activityLevel = 6000),
            )
        ).single()

        assertEquals(70, summary.averageHeartRate)
        assertEquals(7.5f, summary.averageSleepHours, 0.001f)
        assertEquals(5000, summary.averageActivityLevel)
    }

    /**
     * A day with no readings is absent, not zero-filled.
     *
     * "The watch was not worn" and "the watch was worn and showed no stress" are different facts,
     * and only the caller knows which it needs. Zero-filling here would quietly turn the first
     * into the second.
     */
    @Test
    fun daysWithoutReadingsAreAbsentRatherThanZeroFilled() {
        val summaries = summarise(
            listOf(
                prediction(noonUtc),
                prediction(noonUtc + TimeUnit.DAYS.toMillis(3)),
            )
        )

        assertEquals(2, summaries.size)
        assertEquals(listOf("2026-07-23", "2026-07-20"), summaries.map { it.date })
    }

    @Test
    fun emptyHistoryProducesNoSummaries() {
        assertTrue(summarise(emptyList()).isEmpty())
        assertEquals(0, StressHistory.highStressDayCount(emptyList()))
    }

    /** The high class is taken from the manifest, so a three-level bundle must work unchanged. */
    @Test
    fun highStressClassIndexIsNotHardcoded() {
        val threeLevelHigh = 2
        val predictions = List(StressAlertPolicy.THRESHOLD) {
            prediction(noonUtc, classIndex = threeLevelHigh)
        }

        val summaries = StressHistory.summarise(
            predictions,
            highStressClassIndex = threeLevelHigh,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertTrue(summaries.single().isHighStressDay)
    }
}
