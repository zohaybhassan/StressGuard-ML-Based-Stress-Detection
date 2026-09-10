package com.example.stressguard.data

import com.example.stressguard.data.local.WorkoutSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSessionMathTest {

    @Test
    fun `active elapsed time excludes completed pauses`() {
        assertEquals(
            20 * 60_000L,
            WorkoutSessionMath.elapsedActiveMs(
                startedAtEpochMs = 0L,
                plannedEndAtEpochMs = 60 * 60_000L,
                endedAtEpochMs = null,
                status = WorkoutSessionStatus.ACTIVE,
                pausedAtEpochMs = null,
                totalPausedMs = 10 * 60_000L,
                nowEpochMs = 30 * 60_000L,
            )
        )
    }

    @Test
    fun `paused elapsed time excludes the current pause`() {
        assertEquals(
            15 * 60_000L,
            WorkoutSessionMath.elapsedActiveMs(
                startedAtEpochMs = 0L,
                plannedEndAtEpochMs = 60 * 60_000L,
                endedAtEpochMs = null,
                status = WorkoutSessionStatus.PAUSED,
                pausedAtEpochMs = 15 * 60_000L,
                totalPausedMs = 0L,
                nowEpochMs = 25 * 60_000L,
            )
        )
    }

    @Test
    fun `average heart rate is null without samples`() {
        assertNull(WorkoutSessionMath.averageHeartRate(sum = 0L, samples = 0))
    }

    @Test
    fun `average heart rate uses integer bpm`() {
        assertEquals(120, WorkoutSessionMath.averageHeartRate(sum = 360L, samples = 3))
    }

    @Test
    fun `step delta never goes negative`() {
        assertEquals(0, WorkoutSessionMath.stepDelta(firstSteps = 4000, lastSteps = 200))
    }

    @Test
    fun `step delta counts steps during the session`() {
        assertEquals(900, WorkoutSessionMath.stepDelta(firstSteps = 4000, lastSteps = 4900))
    }
}
