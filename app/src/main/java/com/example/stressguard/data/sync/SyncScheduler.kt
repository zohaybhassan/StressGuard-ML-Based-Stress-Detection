package com.example.stressguard.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * When the sync worker runs.
 *
 * The connectivity constraint is doing the real work here: WorkManager holds the job until the
 * device has a network and then runs it, which is precisely plan §15's "restore internet and
 * confirm sync occurs" without the app needing a connectivity listener of its own.
 */
object SyncScheduler {

    private const val PERIODIC_NAME = "supabase-sync"
    private const val ONE_OFF_NAME = "supabase-sync-now"

    /**
     * Thirty minutes. WorkManager's floor is fifteen, and the data is not time-critical: it exists
     * for history and reporting, not for anything the user is waiting on. A longer period means
     * fewer radio wakeups on a phone that is also receiving watch batches.
     */
    private const val PERIOD_MINUTES = 30L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Ensures the periodic sync is scheduled. Safe to call on every launch.
     *
     * [ExistingPeriodicWorkPolicy.KEEP] rather than UPDATE: replacing the request would restart
     * its period, so an app opened frequently would push the next run further away each time and
     * could sync less often than never calling it twice.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SupabaseSyncWorker>(
            PERIOD_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Runs a sync as soon as there is a network.
     *
     * For the moments where waiting up to half an hour would be wrong: just after signing in,
     * when rows collected beforehand finally have a user to belong to, and for a manual "sync now".
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SupabaseSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** True while a sync is queued or running, so the dashboard can say "Syncing…". */
    fun isRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ONE_OFF_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
}
