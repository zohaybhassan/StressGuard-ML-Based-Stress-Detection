package com.example.stressguard.data

import android.content.Context
import com.example.stressguard.SessionManager
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.WorkoutSessionDao
import com.example.stressguard.data.local.WorkoutSessionEntity
import com.example.stressguard.data.local.WorkoutSessionStatus

data class WorkoutSessionSummary(
    val id: Long,
    val startedAtEpochMs: Long,
    val plannedEndAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: String,
    val elapsedActiveMs: Long,
    val remainingMs: Long,
    val averageHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val stepCount: Int,
    val heartRateSamples: Int,
) {
    val isActive: Boolean = status == WorkoutSessionStatus.ACTIVE
    val isPaused: Boolean = status == WorkoutSessionStatus.PAUSED
    val isCompleted: Boolean = status == WorkoutSessionStatus.COMPLETED
}

object WorkoutSessionRepository {

    private const val DEFAULT_HISTORY_LIMIT = 10

    suspend fun start(
        context: Context,
        durationMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WorkoutSessionSummary {
        finishExpiredIfNeeded(context, nowEpochMs)
        val database = StressGuardDatabase.get(context)
        database.workoutSessions().current()?.let { end(context, nowEpochMs) }

        val until = nowEpochMs + durationMs.coerceAtLeast(1L)
        SessionManager.startWorkoutModeUntil(context, until)
        val id = database.workoutSessions().insert(
            WorkoutSessionEntity(
                startedAtEpochMs = nowEpochMs,
                plannedEndAtEpochMs = until,
                status = WorkoutSessionStatus.ACTIVE,
                updatedAtEpochMs = nowEpochMs,
            )
        )
        return database.workoutSessions().byId(id)!!.toSummary(nowEpochMs)
    }

    suspend fun current(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WorkoutSessionSummary? {
        finishExpiredIfNeeded(context, nowEpochMs)
        return StressGuardDatabase.get(context).workoutSessions().current()?.toSummary(nowEpochMs)
    }

    suspend fun history(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): List<WorkoutSessionSummary> {
        finishExpiredIfNeeded(context, nowEpochMs)
        return StressGuardDatabase.get(context).workoutSessions().latest(limit).map { it.toSummary(nowEpochMs) }
    }

    suspend fun pause(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WorkoutSessionSummary? {
        val dao = StressGuardDatabase.get(context).workoutSessions()
        val session = dao.current() ?: return null
        if (session.status != WorkoutSessionStatus.ACTIVE) return session.toSummary(nowEpochMs)

        val updated = session.copy(
            status = WorkoutSessionStatus.PAUSED,
            pausedAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            synced = false,
        )
        dao.update(updated)
        return updated.toSummary(nowEpochMs)
    }

    suspend fun resume(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WorkoutSessionSummary? {
        val dao = StressGuardDatabase.get(context).workoutSessions()
        val session = dao.current() ?: return null
        if (session.status != WorkoutSessionStatus.PAUSED) return session.toSummary(nowEpochMs)

        val pauseMs = session.pausedAtEpochMs?.let { nowEpochMs - it }?.coerceAtLeast(0L) ?: 0L
        val extendedUntil = session.plannedEndAtEpochMs + pauseMs
        SessionManager.startWorkoutModeUntil(context, extendedUntil)
        val updated = session.copy(
            status = WorkoutSessionStatus.ACTIVE,
            plannedEndAtEpochMs = extendedUntil,
            pausedAtEpochMs = null,
            totalPausedMs = session.totalPausedMs + pauseMs,
            updatedAtEpochMs = nowEpochMs,
            synced = false,
        )
        dao.update(updated)
        return updated.toSummary(nowEpochMs)
    }

    suspend fun end(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): WorkoutSessionSummary? {
        val dao = StressGuardDatabase.get(context).workoutSessions()
        val session = dao.current() ?: run {
            SessionManager.clearWorkoutMode(context)
            return null
        }

        val pausedMs = if (session.status == WorkoutSessionStatus.PAUSED) {
            session.pausedAtEpochMs?.let { nowEpochMs - it }?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        val updated = session.copy(
            status = WorkoutSessionStatus.COMPLETED,
            endedAtEpochMs = nowEpochMs,
            pausedAtEpochMs = null,
            totalPausedMs = session.totalPausedMs + pausedMs,
            updatedAtEpochMs = nowEpochMs,
            synced = false,
        )
        dao.update(updated)
        SessionManager.clearWorkoutMode(context)
        return updated.toSummary(nowEpochMs)
    }

    suspend fun recordWorkoutReading(
        context: Context,
        reading: SensorReading,
        nowEpochMs: Long = reading.receivedAtEpochMs,
    ) {
        val dao = StressGuardDatabase.get(context).workoutSessions()
        val session = dao.current() ?: return
        if (session.status != WorkoutSessionStatus.ACTIVE) return

        val updated = session.copy(
            firstSteps = session.firstSteps ?: reading.dailySteps,
            lastSteps = maxOf(session.lastSteps ?: reading.dailySteps, reading.dailySteps),
            minHeartRate = minOf(session.minHeartRate ?: reading.heartRate, reading.heartRate),
            maxHeartRate = maxOf(session.maxHeartRate ?: reading.heartRate, reading.heartRate),
            heartRateSum = session.heartRateSum + reading.heartRate,
            heartRateSamples = session.heartRateSamples + 1,
            updatedAtEpochMs = nowEpochMs,
            synced = false,
        )
        dao.update(updated)
    }

    private suspend fun finishExpiredIfNeeded(context: Context, nowEpochMs: Long) {
        val dao = StressGuardDatabase.get(context).workoutSessions()
        val session = dao.current() ?: return
        if (session.status == WorkoutSessionStatus.ACTIVE && session.plannedEndAtEpochMs <= nowEpochMs) {
            finishSession(context, dao, session, session.plannedEndAtEpochMs)
        } else if (!SessionManager.isWorkoutModeActive(SessionManager.getWorkoutModeUntil(context), nowEpochMs)) {
            finishSession(context, dao, session, nowEpochMs)
        }
    }

    private suspend fun finishSession(
        context: Context,
        dao: WorkoutSessionDao,
        session: WorkoutSessionEntity,
        endedAtEpochMs: Long,
    ) {
        val pausedMs = if (session.status == WorkoutSessionStatus.PAUSED) {
            session.pausedAtEpochMs?.let { endedAtEpochMs - it }?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        dao.update(
            session.copy(
                status = WorkoutSessionStatus.COMPLETED,
                endedAtEpochMs = endedAtEpochMs,
                pausedAtEpochMs = null,
                totalPausedMs = session.totalPausedMs + pausedMs,
                updatedAtEpochMs = endedAtEpochMs,
                synced = false,
            )
        )
        SessionManager.clearWorkoutMode(context)
    }

    private fun WorkoutSessionEntity.toSummary(nowEpochMs: Long): WorkoutSessionSummary {
        return WorkoutSessionSummary(
            id = id,
            startedAtEpochMs = startedAtEpochMs,
            plannedEndAtEpochMs = plannedEndAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            status = status,
            elapsedActiveMs = WorkoutSessionMath.elapsedActiveMs(
                startedAtEpochMs = startedAtEpochMs,
                plannedEndAtEpochMs = plannedEndAtEpochMs,
                endedAtEpochMs = endedAtEpochMs,
                status = status,
                pausedAtEpochMs = pausedAtEpochMs,
                totalPausedMs = totalPausedMs,
                nowEpochMs = nowEpochMs,
            ),
            remainingMs = (plannedEndAtEpochMs - nowEpochMs).coerceAtLeast(0L),
            averageHeartRate = WorkoutSessionMath.averageHeartRate(heartRateSum, heartRateSamples),
            minHeartRate = minHeartRate,
            maxHeartRate = maxHeartRate,
            stepCount = WorkoutSessionMath.stepDelta(firstSteps, lastSteps),
            heartRateSamples = heartRateSamples,
        )
    }
}
