package com.example.stressguard.data

import com.example.stressguard.data.local.DailyStepSource

object StepReconciliationPolicy {
    const val CORRECTION_THRESHOLD_STEPS = 500

    fun shouldCorrect(
        healthConnectSteps: Int,
        stressGuardSteps: Int?,
        stressGuardSource: String?,
        threshold: Int = CORRECTION_THRESHOLD_STEPS,
    ): Boolean =
        stressGuardSource != DailyStepSource.WATCH &&
            stressGuardSource != DailyStepSource.LEGACY &&
            healthConnectSteps.toLong() > (stressGuardSteps ?: 0).toLong() + threshold
}
