package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the trends screen decides to draw.
 *
 * The interesting cases are the sparse ones, because with passive collection they are routine
 * rather than exceptional: the watch is not worn every day, and a chart drawn from one day would
 * imply a week of evidence that does not exist.
 */
class StressTrendsTest {

    private fun day(index: Int, high: Int, readings: Int = 20) = DailyStressSummary(
        date = "2026-07-%02d".format(index + 1),
        readings = readings,
        highStressReadings = high,
        averageHeartRate = 72,
        averageSleepHours = 7.4f,
        averageActivityLevel = 8200,
    )

    private fun trends(days: List<DailyStressSummary>, todayHigh: Int = 0, todayReadings: Int = 0) =
        StressTrends(days = days, highStressReadingsToday = todayHigh, readingsToday = todayReadings)

    @Test
    fun noReadingsAtAllIsNotChartable() {
        val empty = StressTrends.EMPTY

        assertFalse(empty.hasData)
        assertTrue(empty.isTooSparseToChart)
        assertEquals(0, empty.daysWithData)
        assertEquals(0, empty.highStressDays)
    }

    /**
     * One day is data, but not a trend.
     *
     * The two states are distinguished because the screen says different things: "wear your watch
     * for a day" versus "a trend needs at least two".
     */
    @Test
    fun oneDayHasDataButIsStillNotChartable() {
        val single = trends(listOf(day(0, high = 5)))

        assertTrue(single.hasData)
        assertTrue(single.isTooSparseToChart)
        assertEquals(1, single.daysWithData)
    }

    @Test
    fun twoDaysIsEnoughToChart() {
        val pair = trends(listOf(day(0, high = 1), day(1, high = 4)))

        assertTrue(pair.hasData)
        assertFalse(pair.isTooSparseToChart)
        assertEquals(2, pair.daysWithData)
    }

    /** The chart's day count uses the same threshold as the alerts and the risk score. */
    @Test
    fun highStressDaysUsesTheSharedThreshold() {
        val below = StressAlertPolicy.THRESHOLD - 1
        val at = StressAlertPolicy.THRESHOLD

        val week = trends(
            listOf(day(0, high = 0), day(1, high = below), day(2, high = at), day(3, high = at + 9))
        )

        assertEquals(2, week.highStressDays)
    }

    /**
     * Days with no readings are absent, so `daysWithData` is not the window length.
     *
     * The headline quotes it for that reason: "3 high-stress days" means something different across
     * three days of data than across seven, and the user cannot tell which without being told.
     */
    @Test
    fun daysWithDataCountsOnlyDaysThatHaveSome() {
        val patchy = trends(listOf(day(0, high = 4), day(3, high = 0), day(6, high = 5)))

        assertEquals(3, patchy.daysWithData)
        assertEquals(2, patchy.highStressDays)
    }

    /**
     * Today's figures are separate from the rollup.
     *
     * A day that has not yet reached the threshold still has readings worth showing, so the
     * headline reports the live count rather than whether the day qualified.
     */
    @Test
    fun todaysCountIsIndependentOfWhetherTodayQualifies() {
        val partial = trends(
            days = listOf(day(0, high = 0), day(1, high = 1)),
            todayHigh = 1,
            todayReadings = 6,
        )

        assertEquals(1, partial.highStressReadingsToday)
        assertEquals(6, partial.readingsToday)
        assertEquals("today has not qualified as a high-stress day", 0, partial.highStressDays)
    }
}
