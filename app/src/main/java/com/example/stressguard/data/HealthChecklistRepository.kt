package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.data.local.HealthChecklistEntity
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.sync.HealthChecklistRow
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The user's self-reported risk factors, kept locally and mirrored to Supabase.
 *
 * Same division as [ProfileRepository], for the same reason: local storage is authoritative because
 * the recommendation has to be computable with no network, and Supabase is the durable copy that
 * survives a reinstall.
 *
 * The one difference is durability of the upload. A profile push that fails is retried on the next
 * launch and nothing else notices. A checklist save is a deliberate act the user expects to stick,
 * so the row carries a `synced` flag and `SupabaseSyncWorker` drains it — an answer given on a train
 * with no signal reaches the server later without the user repeating themselves.
 */
object HealthChecklistRepository {

    private const val TABLE = "health_checklists"
    private const val TAG = "CHECKLIST_SYNC"
    private const val PULL_TIMEOUT_MS = 4_000L

    /**
     * Saves the answers locally, then tries to push them.
     *
     * The local write happens first and is not conditional on the network: the risk score reads
     * from Room, so the recommendation must update the moment the user finishes the form whether or
     * not anything reaches Supabase.
     */
    suspend fun save(context: Context, checklist: HealthChecklistEntity) {
        val dao = StressGuardDatabase.get(context).healthChecklists()
        dao.save(checklist.copy(synced = false))

        if (push(context, checklist)) {
            dao.markSynced(listOf(checklist.id))
        }
        // Otherwise the row stays unsynced and the worker takes it. Nothing is reported to the
        // user, because from their point of view the save succeeded -- and it did.
    }

    /** @return whether the row reached Supabase. */
    private suspend fun push(context: Context, checklist: HealthChecklistEntity): Boolean {
        val userId = AuthRepository.currentUser?.id
        if (userId == null) {
            Log.w(TAG, "not signed in; keeping the checklist local until sign-in")
            return false
        }

        return try {
            SupabaseProvider.client.from(TABLE).upsert(
                HealthChecklistRow.from(checklist, userId)
            ) {
                onConflict = "user_id"
            }
            Log.i(TAG, "checklist pushed for $userId")
            true
        } catch (error: Exception) {
            Log.w(TAG, "checklist push failed; queued for the sync worker", error)
            false
        }
    }

    /** The stored answers, or null if the user has never filled the form in on this device. */
    suspend fun current(context: Context): HealthChecklistEntity? =
        runCatching { StressGuardDatabase.get(context).healthChecklists().current() }.getOrNull()

    /**
     * Whether a checklist is available locally, recovering it from Supabase if not.
     *
     * Mirrors [ProfileRepository.ensureLocalProfile], and exists for the same failure: after a
     * reinstall the answers are sitting in the database, and without this the app would ask a user
     * to re-declare their medical conditions because the local table happened to be empty.
     *
     * Time-bounded — with no network the user fills the form in again, which is the same outcome as
     * before this existed rather than a worse one.
     */
    suspend fun ensureLocal(context: Context, timeoutMs: Long = PULL_TIMEOUT_MS): Boolean {
        if (current(context) != null) return true
        return withTimeoutOrNull(timeoutMs) { pull(context) } ?: false
    }

    /** Fetches the checklist for the signed-in user and caches it locally. */
    suspend fun pull(context: Context): Boolean {
        val userId = AuthRepository.currentUser?.id ?: return false

        val row = try {
            SupabaseProvider.client.from(TABLE)
                .select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<HealthChecklistRow>()
        } catch (error: Exception) {
            Log.w(TAG, "checklist pull failed; using whatever is stored locally", error)
            return false
        }

        if (row == null) {
            Log.d(TAG, "no checklist stored for $userId")
            return false
        }

        // Marked synced: it came *from* the server, so re-uploading it would be pointless work
        // and would push a fresher `updated_at` over the answer's real age.
        StressGuardDatabase.get(context).healthChecklists().save(row.toEntity(synced = true))
        Log.i(TAG, "checklist restored from Supabase for $userId")
        return true
    }

    /**
     * Drops the local checklist.
     *
     * Called on sign-out. The table is keyed by a constant rather than by user, so a row left
     * behind would be read as the next signed-in user's medical answers, and the risk score would
     * use them with no way to tell.
     */
    suspend fun clearLocal(context: Context) {
        runCatching { StressGuardDatabase.get(context).healthChecklists().clear() }
    }
}
