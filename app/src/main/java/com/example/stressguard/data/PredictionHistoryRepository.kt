package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.SessionManager
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.sync.StressPredictionRow
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

/** Restores local history after logout has removed the previous session's Room data. */
object PredictionHistoryRepository {

    private const val TAG = "STRESS_HISTORY"
    private const val TABLE = "stress_predictions"
    private const val PAGE_SIZE = 500L
    private const val PULL_TIMEOUT_MS = 10_000L
    private const val HISTORY_DAYS = 14L
    private val REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    /**
     * Pulls recent history at most once per refresh interval.
     *
     * Logout clears the successful-restore timestamp with the rest of SessionManager, so signing
     * back in always pulls. Offline failures are not cached and are retried on the next launch.
     */
    suspend fun ensureRecentLocal(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = PULL_TIMEOUT_MS,
    ): Boolean {
        if (!SupabaseConfig.isBackendConfigured || AuthRepository.currentUser == null) return false
        val lastRestore = SessionManager.getHistoryRestoredAt(context)
        if (lastRestore in 1..nowEpochMs && nowEpochMs - lastRestore < REFRESH_INTERVAL_MS) {
            return true
        }

        val restored = withTimeoutOrNull(timeoutMs) { pull(context, nowEpochMs) } ?: false
        if (restored) SessionManager.markHistoryRestored(context, nowEpochMs)
        return restored
    }

    /** Fetches every page in the recommendation window and merges it into Room. */
    suspend fun pull(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val userId = AuthRepository.currentUser?.id ?: return false
        val cutoff = Instant.ofEpochMilli(
            nowEpochMs - TimeUnit.DAYS.toMillis(HISTORY_DAYS)
        ).toString()

        return try {
            val restored = mutableListOf<StressPredictionRow>()
            var offset = 0L
            do {
                val page = SupabaseProvider.client.from(TABLE)
                    .select {
                        filter {
                            eq("user_id", userId)
                            gte("recorded_at", cutoff)
                        }
                        order("recorded_at", Order.ASCENDING)
                        range(offset, offset + PAGE_SIZE - 1)
                    }
                    .decodeList<StressPredictionRow>()
                restored += page
                offset += page.size
            } while (page.size == PAGE_SIZE.toInt())

            val inserted = StressGuardDatabase.get(context).stressPredictions()
                .mergeRestored(restored.map { it.toEntity() })
            Log.i(TAG, "restored $inserted of ${restored.size} recent predictions for $userId")
            true
        } catch (error: Exception) {
            Log.w(TAG, "recent prediction restore failed; keeping local history", error)
            false
        }
    }
}
