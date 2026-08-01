package com.example.stressguard.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepDayAggregatorTest {
    private val zone = ZoneId.of("Asia/Karachi")

    @Test
    fun `evening nap augments the main sleep for the same day`() {
        val night = interval("2026-07-30T22:00:00Z", "2026-07-31T03:00:00Z")
        val nap = interval("2026-07-31T11:00:00Z", "2026-07-31T12:30:00Z")

        val day = SleepDayAggregator.latest(listOf(night, nap), zone)!!

        assertEquals(Duration.ofHours(5), day.mainSleep.duration)
        assertEquals(Duration.ofMinutes(90), day.naps.single().duration)
        assertEquals(Duration.ofMinutes(390), day.totalDuration)
    }

    @Test
    fun `latest sleep day does not sum previous nights`() {
        val oldNight = interval("2026-07-29T21:00:00Z", "2026-07-30T04:00:00Z")
        val latestNight = interval("2026-07-30T22:00:00Z", "2026-07-31T03:00:00Z")

        val day = SleepDayAggregator.latest(listOf(oldNight, latestNight), zone)!!

        assertEquals(Duration.ofHours(5), day.totalDuration)
    }

    @Test
    fun `nearby fragments form one main sleep without counting the gap`() {
        val first = interval("2026-07-30T22:00:00Z", "2026-07-31T00:00:00Z")
        val second = interval("2026-07-31T00:30:00Z", "2026-07-31T03:30:00Z")

        val day = SleepDayAggregator.latest(listOf(first, second), zone)!!

        assertEquals(Duration.ofHours(5), day.mainSleep.duration)
        assertEquals(0, day.naps.size)
    }

    @Test
    fun `fragment ending before midnight stays with the wake date`() {
        val beforeMidnight = interval("2026-07-30T17:00:00Z", "2026-07-30T18:30:00Z")
        val afterMidnight = interval("2026-07-30T19:00:00Z", "2026-07-31T00:30:00Z")

        val day = SleepDayAggregator.latest(listOf(beforeMidnight, afterMidnight), zone)!!

        assertEquals(Duration.ofHours(7), day.mainSleep.duration)
        assertEquals(Duration.ofHours(7), day.totalDuration)
    }

    private fun interval(start: String, end: String) = SleepInterval(
        start = Instant.parse(start),
        end = Instant.parse(end),
    )
}
