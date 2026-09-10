package com.example.stressguard.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object StepReconciliationScheduler {

    private const val PERIODIC_NAME = "health-connect-step-reconciliation"
    private const val ONE_OFF_NAME = "health-connect-step-reconciliation-now"
    const val PERIOD_MINUTES = 60L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<StepReconciliationWorker>(
            PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun reconcileNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<StepReconciliationWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
