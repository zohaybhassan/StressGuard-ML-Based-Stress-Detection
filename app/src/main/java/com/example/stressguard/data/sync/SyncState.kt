package com.example.stressguard.data.sync

import android.content.Context

/**
 * When the last successful sync happened, and how much is still waiting.
 *
 * The timestamp lives in SharedPreferences rather than being derived from WorkManager: a
 * `WorkInfo` says a run *succeeded*, not that anything was uploaded, and it is pruned after a
 * while. What the dashboard needs to show is "your data reached the server at this time", which
 * only the worker knows.
 */
class SyncState(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the last successful run, or null if one has never completed. */
    val lastSuccessEpochMs: Long?
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L }

    fun recordSuccess(atEpochMs: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESS, atEpochMs).apply()
    }

    private companion object {
        const val NAME = "sync_state"
        const val KEY_LAST_SUCCESS = "last_success_epoch_ms"
    }
}

/** Everything the dashboard needs to describe sync in one line. */
data class SyncStatus(
    val pending: Int,
    val lastSuccessEpochMs: Long?,
    val signedIn: Boolean,
    val backendConfigured: Boolean,
) {
    companion object {
        val UNKNOWN = SyncStatus(
            pending = 0,
            lastSuccessEpochMs = null,
            signedIn = false,
            backendConfigured = false,
        )
    }

    /**
     * A sentence for the dashboard.
     *
     * Says why nothing is syncing when nothing is, rather than showing a stale "last synced" and
     * letting the user assume their data is safe. A pending count with no explanation was the
     * failure mode worth avoiding: 99 rows queued looks like a bug until you know no one is
     * signed in.
     */
    fun describe(nowEpochMs: Long): String = when {
        !backendConfigured -> "Backend not configured — $pending stored locally"
        !signedIn -> "Sign in to sync — $pending waiting"
        pending == 0 && lastSuccessEpochMs != null ->
            "Synced ${ago(nowEpochMs - lastSuccessEpochMs)}"
        pending == 0 -> "Nothing to sync"
        lastSuccessEpochMs == null -> "$pending waiting to sync"
        else -> "$pending waiting — last synced ${ago(nowEpochMs - lastSuccessEpochMs)}"
    }

    private fun ago(elapsedMs: Long): String = when {
        // A backwards wall clock would otherwise read as a sync in the future.
        elapsedMs < 0 -> "recently"
        elapsedMs < 60_000 -> "just now"
        elapsedMs < 3_600_000 -> "${elapsedMs / 60_000} min ago"
        elapsedMs < 86_400_000 -> "${elapsedMs / 3_600_000} h ago"
        else -> "${elapsedMs / 86_400_000} d ago"
    }
}
