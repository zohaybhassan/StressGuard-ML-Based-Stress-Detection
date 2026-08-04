package com.example.stressguard.data

object StepReconciliationPolicy {
    const val CORRECTION_THRESHOLD_STEPS = 500

    fun shouldCorrect(
        healthConnectSteps: Int,
        stressGuardSteps: Int?,
        threshold: Int = CORRECTION_THRESHOLD_STEPS,
    ): Boolean = healthConnectSteps.toLong() > (stressGuardSteps ?: 0).toLong() + threshold
}
