package com.example.stressguard.data

import com.example.stressguard.data.local.WorkoutSessionStatus

object WorkoutSessionMath {

    fun elapsedActiveMs(
        startedAtEpochMs: Long,
        plannedEndAtEpochMs: Long,
        endedAtEpochMs: Long?,
        status: String,
        pausedAtEpochMs: Long?,
        totalPausedMs: Long,
        nowEpochMs: Long,
    ): Long {
        val effectiveEnd = endedAtEpochMs ?: nowEpochMs.coerceAtMost(plannedEndAtEpochMs)
        val currentPauseMs = if (status == WorkoutSessionStatus.PAUSED) {
            pausedAtEpochMs?.let { nowEpochMs - it }?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        val completedPauseMs = totalPausedMs.coerceAtLeast(0L)
        return (effectiveEnd - startedAtEpochMs - completedPauseMs - currentPauseMs)
            .coerceAtLeast(0L)
    }

    fun averageHeartRate(sum: Long, samples: Int): Int? =
        if (samples > 0) (sum / samples).toInt() else null

    fun stepDelta(firstSteps: Int?, lastSteps: Int?): Int {
        if (firstSteps == null || lastSteps == null) return 0
        return (lastSteps.toLong() - firstSteps.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }
}
