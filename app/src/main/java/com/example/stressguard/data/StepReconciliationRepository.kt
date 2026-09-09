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
 * Connect and uses its total only while StressGuard has no watch-owned count for the day. A
 * Health Connect aggregate may include phone and Samsung wearable data, so it must never replace
 * the live watch source that the dashboard promises to show.
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
        val stressGuardEntry = dao.entryFor(dateKey)
        val stressGuardSteps = stressGuardEntry?.steps

        val shouldCorrect = StepReconciliationPolicy.shouldCorrect(
            healthConnectSteps = healthConnectSteps,
            stressGuardSteps = stressGuardSteps,
            stressGuardSource = stressGuardEntry?.source,
        )
        if (shouldCorrect) {
            dao.upsertHealthConnectFallback(dateKey, healthConnectSteps, now.toEpochMilli())
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
