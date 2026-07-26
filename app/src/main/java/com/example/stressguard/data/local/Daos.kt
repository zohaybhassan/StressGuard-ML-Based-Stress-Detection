package com.example.stressguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Every DAO here exposes the same four shapes, because Phase 7's sync worker needs them all:
 * insert, read recent, read unsynced, mark synced. Plus a purge for the retention policy.
 */

@Dao
interface StressPredictionDao {

    @Insert
    suspend fun insert(prediction: StressPredictionEntity): Long

    /** Newest first. Backs the dashboard and, later, the trends screen. */
    @Query("SELECT * FROM stress_predictions ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<StressPredictionEntity>

    /** Live view for the dashboard, so a new prediction updates it without a manual refresh. */
    @Query("SELECT * FROM stress_predictions ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<StressPredictionEntity>>

    @Query("SELECT * FROM stress_predictions WHERE recordedAtEpochMs >= :sinceEpochMs ORDER BY recordedAtEpochMs ASC")
    suspend fun since(sinceEpochMs: Long): List<StressPredictionEntity>

    /** Count of a given class since a timestamp — today's high-stress count, per plan §17. */
    @Query("SELECT COUNT(*) FROM stress_predictions WHERE classIndex = :classIndex AND recordedAtEpochMs >= :sinceEpochMs")
    suspend fun countOfClassSince(classIndex: Int, sinceEpochMs: Long): Int

    @Query("SELECT * FROM stress_predictions WHERE synced = 0 ORDER BY recordedAtEpochMs ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 500): List<StressPredictionEntity>

    @Query("UPDATE stress_predictions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /**
     * Retention. Only removes rows already synced, so turning the network off for a month
     * cannot silently discard data that never reached the backend.
     */
    @Query("DELETE FROM stress_predictions WHERE recordedAtEpochMs < :cutoffEpochMs AND synced = 1")
    suspend fun deleteSyncedOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM stress_predictions WHERE synced = 0")
    suspend fun countUnsynced(): Int

    @Query("SELECT COUNT(*) FROM stress_predictions")
    suspend fun count(): Int
}

@Dao
interface LatencyMetricDao {

    @Insert
    suspend fun insert(metric: LatencyMetricEntity): Long

    @Query("SELECT * FROM latency_metrics ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<LatencyMetricEntity>

    @Query("SELECT * FROM latency_metrics ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<LatencyMetricEntity>>

    /**
     * Averages exclude cold starts, which include loading ~13.8 MB of ONNX and are not
     * representative of the steady-state path the latency claim is about.
     */
    @Query("SELECT AVG(receiveToPredictionMs) FROM latency_metrics WHERE coldStart = 0")
    suspend fun averageReceiveToPredictionMs(): Double?

    @Query("SELECT AVG(totalMs) FROM latency_metrics WHERE coldStart = 0")
    suspend fun averageTotalMs(): Double?

    @Query("SELECT AVG(predictionToAlertMs) FROM latency_metrics WHERE predictionToAlertMs IS NOT NULL")
    suspend fun averagePredictionToAlertMs(): Double?

    @Query("SELECT COUNT(*) FROM latency_metrics WHERE coldStart = 0")
    suspend fun steadyStateSampleCount(): Int

    @Query("SELECT * FROM latency_metrics WHERE synced = 0 ORDER BY recordedAtEpochMs ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 500): List<LatencyMetricEntity>

    @Query("UPDATE latency_metrics SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM latency_metrics WHERE recordedAtEpochMs < :cutoffEpochMs AND synced = 1")
    suspend fun deleteSyncedOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM latency_metrics WHERE synced = 0")
    suspend fun countUnsynced(): Int

    @Query("SELECT COUNT(*) FROM latency_metrics")
    suspend fun count(): Int
}

@Dao
interface DailyStepTotalDao {

    /**
     * Records a step count for a day, keeping whichever figure is higher.
     *
     * Highest rather than latest, because the watch's daily counter resets at midnight and a
     * reading that lands just after the reset would otherwise overwrite the whole day's total
     * with a number near zero — the exact failure this table exists to prevent.
     */
    @Query(
        """
        INSERT INTO daily_step_totals (date, steps, updatedAtEpochMs)
        VALUES (:date, :steps, :updatedAtEpochMs)
        ON CONFLICT(date) DO UPDATE SET
            steps = MAX(steps, excluded.steps),
            updatedAtEpochMs = excluded.updatedAtEpochMs
        """
    )
    suspend fun upsertMax(date: String, steps: Int, updatedAtEpochMs: Long)

    @Query("SELECT steps FROM daily_step_totals WHERE date = :date")
    suspend fun totalFor(date: String): Int?

    /**
     * The most recent day before [beforeDate] that has a total, newest first.
     *
     * A gap is normal — the app is not worn every day — so this looks back rather than assuming
     * yesterday exists.
     */
    @Query("SELECT * FROM daily_step_totals WHERE date < :beforeDate ORDER BY date DESC LIMIT 1")
    suspend fun mostRecentBefore(beforeDate: String): DailyStepTotalEntity?

    @Query("DELETE FROM daily_step_totals WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String): Int

    @Query("SELECT COUNT(*) FROM daily_step_totals")
    suspend fun count(): Int
}

@Dao
interface AlertEventDao {

    @Insert
    suspend fun insert(alert: AlertEventEntity): Long

    /** Drives the cooldown, so it must survive a process restart. */
    @Query("SELECT * FROM alert_events ORDER BY firedAtEpochMs DESC LIMIT 1")
    suspend fun mostRecent(): AlertEventEntity?

    @Query("SELECT * FROM alert_events ORDER BY firedAtEpochMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AlertEventEntity>

    @Query("SELECT * FROM alert_events ORDER BY firedAtEpochMs DESC LIMIT 1")
    fun observeMostRecent(): Flow<AlertEventEntity?>

    @Query("SELECT COUNT(*) FROM alert_events WHERE firedAtEpochMs >= :sinceEpochMs")
    suspend fun countSince(sinceEpochMs: Long): Int

    @Query("UPDATE alert_events SET dismissed = 1 WHERE id = :id")
    suspend fun markDismissed(id: Long)

    @Query("SELECT * FROM alert_events WHERE synced = 0 ORDER BY firedAtEpochMs ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 500): List<AlertEventEntity>

    @Query("UPDATE alert_events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM alert_events WHERE firedAtEpochMs < :cutoffEpochMs AND synced = 1")
    suspend fun deleteSyncedOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM alert_events WHERE synced = 0")
    suspend fun countUnsynced(): Int

    @Query("SELECT COUNT(*) FROM alert_events")
    suspend fun count(): Int
}
