package com.example.stressguard.presentation

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService

/**
 * Registration for background vitals collection.
 *
 * `MeasureClient`, which the watch screen uses, is the on-demand API: it measures only while an
 * Activity is in the foreground, so closing the app or letting the screen time out ends
 * collection. That makes it unusable as the app's data source — a stress monitor the user has to
 * keep open measures nothing about their day.
 *
 * `PassiveMonitoringClient` is the API for this. The registration is held by Health Services
 * rather than by this process, so data keeps arriving with the app closed and after it is killed;
 * [PassiveVitalsService] is bound on demand to receive each batch. The cost is granularity:
 * Health Services batches deliveries on its own schedule to save power, so samples arrive every
 * few minutes rather than every second. For stress that is the right trade — the signal does not
 * change second to second, and the optical sensor is the watch's most expensive component to run.
 */
object PassiveVitals {

    /**
     * Heart rate, plus the daily step total.
     *
     * [DataType.STEPS_DAILY] carries steps since midnight and is reset by the platform, which is
     * exactly what the model's "Daily Steps" feature means. [DailyStepCounter] exists because
     * `TYPE_STEP_COUNTER` counts from boot instead and needed a stored baseline; the passive path
     * has no such problem.
     */
    private val DATA_TYPES = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)

    /**
     * Asks Health Services to deliver vitals to [PassiveVitalsService] from now on.
     *
     * Safe to call repeatedly: setting the listener replaces any previous registration. Callers
     * do so on every app start, on boot and after an upgrade, because a registration is not
     * guaranteed to survive any of those.
     */
    suspend fun register(context: Context): Boolean {
        val client = HealthServices.getClient(context).passiveMonitoringClient

        val supported = runCatching { client.getCapabilities() }
            .onFailure { Log.w(TAG, "could not read passive capabilities", it) }
            .getOrNull()
            ?.supportedDataTypesPassiveMonitoring

        if (supported != null) {
            // Registering an unsupported type is not an error, it simply never delivers, so the
            // absence has to be checked rather than waited for.
            val missing = DATA_TYPES - supported
            if (missing.isNotEmpty()) {
                Log.w(TAG, "watch does not support passive $missing; those will never arrive")
            }
            Log.i(TAG, "passive capabilities: $supported")
        }

        val requested = supported?.let { DATA_TYPES intersect it } ?: DATA_TYPES
        if (requested.isEmpty()) {
            Log.e(TAG, "no requested data type is supported passively; background vitals are off")
            return false
        }

        val config = PassiveListenerConfig.Builder()
            .setDataTypes(requested)
            .build()

        return runCatching {
            client.setPassiveListenerService(PassiveVitalsService::class.java, config)
        }
            .onSuccess { Log.i(TAG, "background vitals registered for $requested") }
            .onFailure { Log.e(TAG, "could not register background vitals", it) }
            .isSuccess
    }

    /** Used when the permission is withdrawn, so Health Services stops trying to deliver. */
    suspend fun unregister(context: Context) {
        // The suspend extension, not clearPassiveListenerServiceAsync: the Async variants return
        // a Guava ListenableFuture, which is not on this module's classpath.
        runCatching {
            HealthServices.getClient(context).passiveMonitoringClient
                .clearPassiveListenerService()
        }
            .onSuccess { Log.i(TAG, "background vitals unregistered") }
            .onFailure { Log.w(TAG, "could not unregister background vitals", it) }
    }

    const val TAG = "WEAR_VITALS"
}
