package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.SessionManager
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything on this device that belongs to the signed-in user, and how to remove it.
 *
 * Needed because none of the local storage is keyed by user. The Room tables, the profile in
 * SharedPreferences and the sleep cache all describe "the person using this phone", which was true
 * while there was no way to sign out. Adding one makes it false, and the consequences are not
 * cosmetic:
 *
 *  - `SupabaseSyncWorker` attaches `AuthRepository.currentUser.id` to whatever is queued **at
 *    upload time**. A previous user's unsynced predictions would therefore be uploaded into the
 *    next user's account, under their `user_id`, passing RLS because the client genuinely is
 *    authenticated as them.
 *  - The health checklist is a list of medical conditions. Leaving it behind would attribute one
 *    person's answers to another, and the risk score would use them with no way to notice.
 *  - `StepHistory` and `SleepCache` feed the model directly, so a stale one silently predicts from
 *    someone else's body.
 *
 * Signing out is an explicit act, so discarding unsynced rows is the right trade — but the count is
 * exposed by [pendingUploadCount] so the user can be told before it happens rather than after.
 */
object LocalUserData {

    private const val TAG = "AUTH"

    /**
     * How many locally stored rows have not reached Supabase yet.
     *
     * Worth asking before signing out: these are lost, and a user who has been offline for a week
     * deserves to know that before they tap rather than to discover it afterwards.
     */
    suspend fun pendingUploadCount(context: Context): Int = runCatching {
        val database = StressGuardDatabase.get(context)
        database.stressPredictions().countUnsynced() +
            database.latencyMetrics().countUnsynced() +
            database.alertEvents().countUnsynced() +
            database.healthChecklists().countUnsynced()
    }.getOrDefault(0)

    /**
     * Wipes local user data. Called on sign-out, after the Supabase session has gone.
     *
     * Each step is independently guarded: a failure to clear one store must not leave the others
     * populated, because a partial wipe is the worst outcome — it looks signed out while still
     * holding the previous user's data.
     */
    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        val database = StressGuardDatabase.get(context)

        // `clearAllTables` is blocking and calls Room's own `assertNotMainThread`, so it *must* run
        // off the main thread. Without the dispatcher above it threw every time, `runCatching`
        // swallowed it into a warning, and the database survived a sign-out entirely — the next
        // user saw the previous one's charts, history and health checklist. The failure was silent
        // because everything else on this list clears fine from the main thread.
        val databaseCleared = runCatching { database.clearAllTables() }
            .onFailure {
                // Error, not warning. A database that outlives its owner shows one person's
                // readings and medical answers to another; that is not a degraded state to log
                // quietly and carry on from.
                Log.e(TAG, "FAILED to clear the local database; it still holds the previous user's data", it)
            }
            .isSuccess

        // In-memory state too: the pipeline is process-scoped and outlives every Activity, so
        // clearing only the database would leave the previous user's smoothing window loaded.
        runCatching { StressPipeline.get(context).forgetUser() }
            .onFailure { Log.w(TAG, "could not reset the pipeline", it) }

        runCatching { SessionManager.clear(context) }
            .onFailure { Log.w(TAG, "could not clear the stored profile", it) }

        runCatching { SleepCache(context).clear() }
            .onFailure { Log.w(TAG, "could not clear the sleep cache", it) }

        runCatching { SyncState(context).clear() }
            .onFailure { Log.w(TAG, "could not clear the sync state", it) }

        if (databaseCleared) Log.i(TAG, "local user data cleared")
        databaseCleared
    }
}
