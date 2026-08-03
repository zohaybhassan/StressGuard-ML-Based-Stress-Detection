package com.example.stressguard

import com.example.stressguard.data.SensorReading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staleness rule, which decides whether the dashboard presents a reading as current.
 *
 * Worth pinning down because getting it wrong is silent in both directions: too eager and a
 * live reading is labelled idle, too lax and a heart rate measured minutes ago is shown as the
 * wearer's present state.
 */
class DashboardUiStateTest {

    private fun watchState(ageMs: Long?) = DashboardUiState(
        heartRate = 89,
        source = ReadingSource.WATCH,
        readingAgeMs = ageMs,
    )

    @Test
    fun `a reading that has just arrived is not stale`() {
        assertFalse(watchState(0L).isReadingStale)
    }

    @Test
    fun `a reading just under the threshold is not stale`() {
        assertFalse(watchState(SensorReading.STALE_SAMPLE_MS - 1).isReadingStale)
    }

    @Test
    fun `a reading at the threshold is stale`() {
        assertTrue(watchState(SensorReading.STALE_SAMPLE_MS).isReadingStale)
    }

    @Test
    fun `a reading minutes old is stale`() {
        assertTrue(watchState(5 * 60_000L).isReadingStale)
    }

    @Test
    fun `a debug sample is never stale`() {
        // Debug scenarios carry no arrival time, and ageing one would imply a watch behind it.
        val simulated = DashboardUiState(
            heartRate = 105,
            source = ReadingSource.SIMULATED,
            readingAgeMs = null,
        )
        assertFalse(simulated.isReadingStale)
    }

    @Test
    fun `waiting for a first reading is not stale`() {
        // Nothing is on screen yet, so there is nothing to call old; WatchLink covers this case.
        assertFalse(DashboardUiState().isReadingStale)
    }

    @Test
    fun `workout pause is distinct from stale or error states`() {
        val paused = DashboardUiState(
            heartRate = 132,
            source = ReadingSource.WATCH,
            watchLink = WatchLink.STREAMING,
            readingAgeMs = 0L,
            workoutModeUntilEpochMs = 1_700_000_060_000L,
            error = null,
        )

        assertTrue(paused.isWorkoutPaused)
        assertFalse(paused.isReadingStale)
        assertFalse(paused.watchLink == WatchLink.NO_WATCH)
    }
}
