package com.example.stressguard.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.stressguard.data.local.StressGuardDatabase
import java.time.Instant
import java.time.ZoneId

data class StepReconciliationResult(
    val healthConnectSteps: Int?,
    val stressGuardSteps: Int?,
    val corrected: Boolean,
    val reason: String,
)

/**
 * Reconciles StressGuard's live watch step count with Samsung Health/Health Connect.
 *
 * The live watch stream remains the primary source. Once in a while, the phone reads Health
 * Connect and only raises the local daily total when Health Connect is meaningfully ahead. It
 * never lowers StressGuard's count.
 */
object StepReconciliationRepository {

    private const val TAG = "STEP_RECONCILE"

    val stepPermission: String = HealthPermission.getReadPermission(StepsRecord::class)

    suspend fun reconcileToday(
        context: Context,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StepReconciliationResult {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return StepReconciliationResult(null, null, corrected = false, reason = "unavailable")
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (stepPermission !in granted) {
            return StepReconciliationResult(null, null, corrected = false, reason = "permission")
        }

        val healthConnectSteps = readTodaySteps(client, now, zoneId)
            ?: return StepReconciliationResult(null, null, corrected = false, reason = "no data")
        val dateKey = StepHistory.dateKey(now.toEpochMilli())
        val dao = StressGuardDatabase.get(context).dailyStepTotals()
        val stressGuardSteps = dao.totalFor(dateKey)

        val shouldCorrect = StepReconciliationPolicy.shouldCorrect(
            healthConnectSteps = healthConnectSteps,
            stressGuardSteps = stressGuardSteps,
        )
        if (shouldCorrect) {
            dao.upsertMax(dateKey, healthConnectSteps, now.toEpochMilli())
            Log.i(
                TAG,
                "corrected today's steps from ${stressGuardSteps ?: 0} to $healthConnectSteps"
            )
        }

        return StepReconciliationResult(
            healthConnectSteps = healthConnectSteps,
            stressGuardSteps = stressGuardSteps,
            corrected = shouldCorrect,
            reason = if (shouldCorrect) "corrected" else "within threshold",
        )
    }

    private suspend fun readTodaySteps(
        client: HealthConnectClient,
        now: Instant,
        zoneId: ZoneId,
    ): Int? {
        val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )
        val steps = result[StepsRecord.COUNT_TOTAL] ?: return null
        return steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
