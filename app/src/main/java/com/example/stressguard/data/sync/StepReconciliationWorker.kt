package com.example.stressguard.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stressguard.data.StepReconciliationRepository

class StepReconciliationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = StepReconciliationRepository.reconcileToday(applicationContext)
        Log.i(
            TAG,
            "step reconciliation: ${result.reason}, " +
                "health=${result.healthConnectSteps}, stressguard=${result.stressGuardSteps}"
        )
        Result.success()
    } catch (error: Exception) {
        Log.w(TAG, "step reconciliation failed; will retry later", error)
        Result.retry()
    }

    companion object {
        private const val TAG = "STEP_RECONCILE"
    }
}
