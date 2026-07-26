package com.example.stressguard.data

/** Why an alert did or did not fire. Explicit so the dashboard and tests can say which. */
sealed interface AlertDecision {
    data class Fire(
        val highCount: Int,
        val windowSize: Int,
        val reason: String,
    ) : AlertDecision

    /** The smoothing rule was not met. The ordinary case; not an error. */
    data object NotSustained : AlertDecision

    /** Sustained, but an alert fired too recently. */
    data class InCooldown(val remainingMs: Long) : AlertDecision
}

/**
 * Decides when sustained high stress warrants an alert.
 *
 * Pure, so the rule can be tested exhaustively without a device, a database or a clock.
 *
 * Two mechanisms, doing different jobs:
 *  - **Smoothing** filters noise. A single high reading is not evidence of stress — heart rate
 *    spikes from climbing stairs. Requiring [THRESHOLD] of the last [WINDOW] means a transient
 *    cannot trigger an alert.
 *  - **Cooldown** filters nuisance. Once someone knows they are stressed, telling them again
 *    ninety seconds later is not new information, and an app that buzzes continuously during
 *    a stressful afternoon gets its notifications turned off.
 */
object StressAlertPolicy {

    /** Plan §13: "alert when 3 of the last 5 predictions are high stress". */
    const val WINDOW = 5
    const val THRESHOLD = 3

    /** Plan §13 suggests 10 minutes. */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * @param recentClassIndices predicted classes, oldest first, newest last.
     * @param highStressClassIndex the most severe class. Taken from the manifest's class
     *   count rather than hardcoded, because the shipped bundle is binary while the
     *   three-level bundle remains swappable.
     * @param lastAlertEpochMs when an alert last fired, or null if never.
     */
    fun evaluate(
        recentClassIndices: List<Int>,
        highStressClassIndex: Int,
        nowEpochMs: Long,
        lastAlertEpochMs: Long?,
        cooldownMs: Long = COOLDOWN_MS,
    ): AlertDecision {
        val window = recentClassIndices.takeLast(WINDOW)

        // Fewer readings than the threshold cannot satisfy it, whatever they are.
        if (window.size < THRESHOLD) return AlertDecision.NotSustained

        val highCount = window.count { it == highStressClassIndex }
        if (highCount < THRESHOLD) return AlertDecision.NotSustained

        if (lastAlertEpochMs != null) {
            val elapsed = nowEpochMs - lastAlertEpochMs
            // A negative elapsed means the wall clock moved backwards. Treat it as expired
            // rather than suppressing alerts until the clock catches up.
            if (elapsed in 0 until cooldownMs) {
                return AlertDecision.InCooldown(remainingMs = cooldownMs - elapsed)
            }
        }

        return AlertDecision.Fire(
            highCount = highCount,
            windowSize = window.size,
            reason = "$highCount of the last ${window.size} readings indicated high stress",
        )
    }
}
