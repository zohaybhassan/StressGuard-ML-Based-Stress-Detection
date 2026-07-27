package com.example.stressguard.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.SupabaseConfig
import com.example.stressguard.data.SupabaseProvider
import com.example.stressguard.data.local.StressGuardDatabase
import io.github.jan.supabase.postgrest.from

/**
 * Uploads local history to Supabase, per plan §15.
 *
 * Runs on WorkManager's schedule with a connectivity constraint, never on the prediction path.
 * That separation is the plan's central architectural claim (§4, §25): detection and alerting are
 * local and offline, and the backend is for durability and long-term analysis. Nothing here can
 * make an alert slower, because nothing here is between a reading and an alert.
 *
 * The upload is idempotent. Each table has a `unique (user_id, <event time>)` constraint and the
 * worker upserts against it, so a retry that re-sends rows the server already accepted is ignored
 * rather than duplicated — plan §15's "confirm duplicate records are not created after retry".
 *
 * Rows are marked synced only after the upsert returns. A crash midway therefore re-sends rather
 * than loses, which the unique constraint makes harmless.
 */
class SupabaseSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SupabaseConfig.isBackendConfigured) {
            // A build with no backend configuration is a valid state: the app is fully functional
            // offline. Retrying would achieve nothing, so this is success with nothing to do.
            Log.i(TAG, "no backend configured; nothing to sync")
            return Result.success()
        }

        val userId = AuthRepository.currentUser?.id
        if (userId == null) {
            // Rows stay queued. There is no user_id to attach them to yet, and they will upload
            // once someone signs in -- history collected before sign-in is not thrown away.
            Log.i(TAG, "not signed in; leaving ${pendingCount()} rows queued")
            return Result.success()
        }

        val database = StressGuardDatabase.get(applicationContext)

        return try {
            val predictions = syncPredictions(database, userId)
            val latency = syncLatency(database, userId)
            val alerts = syncAlerts(database, userId)
            val checklists = syncChecklist(database, userId)

            val total = predictions + latency + alerts + checklists
            if (total > 0) {
                Log.i(
                    TAG,
                    "synced $predictions predictions, $latency latency, $alerts alerts, " +
                        "$checklists checklists"
                )
            }
            SyncState(applicationContext).recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (error: Exception) {
            // Network down, Supabase unavailable, token expired. All transient; WorkManager backs
            // off and retries, and the rows are still marked unsynced so nothing is lost.
            Log.w(TAG, "sync failed; rows stay queued for retry", error)
            Result.retry()
        }
    }

    private suspend fun syncPredictions(database: StressGuardDatabase, userId: String): Int {
        val dao = database.stressPredictions()
        val pending = dao.unsynced(BATCH)
        if (pending.isEmpty()) return 0

        SupabaseProvider.client.from("stress_predictions")
            .upsert(pending.map { StressPredictionRow.from(it, userId) }) {
                onConflict = "user_id,recorded_at"
                ignoreDuplicates = true
            }

        dao.markSynced(pending.map { it.id })
        return pending.size
    }

    private suspend fun syncLatency(database: StressGuardDatabase, userId: String): Int {
        val dao = database.latencyMetrics()
        val pending = dao.unsynced(BATCH)
        if (pending.isEmpty()) return 0

        SupabaseProvider.client.from("latency_metrics")
            .upsert(pending.map { LatencyMetricRow.from(it, userId) }) {
                onConflict = "user_id,recorded_at"
                ignoreDuplicates = true
            }

        dao.markSynced(pending.map { it.id })
        return pending.size
    }

    private suspend fun syncAlerts(database: StressGuardDatabase, userId: String): Int {
        val dao = database.alertEvents()
        val pending = dao.unsynced(BATCH)
        if (pending.isEmpty()) return 0

        SupabaseProvider.client.from("alert_events")
            .upsert(pending.map { AlertEventRow.from(it, userId) }) {
                onConflict = "user_id,fired_at"
                ignoreDuplicates = true
            }

        dao.markSynced(pending.map { it.id })
        return pending.size
    }

    /**
     * The checklist differs from the history tables in two ways that both matter here.
     *
     * It is at most one row, and it is *current state* rather than an event, so the conflict target
     * is `user_id` alone and a re-save must overwrite. `ignoreDuplicates` is therefore false: an
     * edit that changed an answer has to replace the stored row, and ignoring the conflict would
     * silently keep the old answers on the server while the app marked them synced.
     */
    private suspend fun syncChecklist(database: StressGuardDatabase, userId: String): Int {
        val dao = database.healthChecklists()
        val pending = dao.unsynced()
        if (pending.isEmpty()) return 0

        SupabaseProvider.client.from("health_checklists")
            .upsert(pending.map { HealthChecklistRow.from(it, userId) }) {
                onConflict = "user_id"
            }

        dao.markSynced(pending.map { it.id })
        return pending.size
    }

    private suspend fun pendingCount(): Int = runCatching {
        val database = StressGuardDatabase.get(applicationContext)
        database.stressPredictions().unsynced(BATCH).size +
            database.latencyMetrics().unsynced(BATCH).size +
            database.alertEvents().unsynced(BATCH).size +
            database.healthChecklists().countUnsynced()
    }.getOrDefault(0)

    companion object {
        const val TAG = "STRESS_SYNC"

        /**
         * Rows per table per run. Bounded so a device that has been offline for a week sends a
         * series of modest requests rather than one that times out; the next run takes the rest.
         */
        private const val BATCH = 500
    }
}
