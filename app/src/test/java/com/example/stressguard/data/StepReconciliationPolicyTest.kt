package com.example.stressguard.data

import com.example.stressguard.data.local.DailyStepSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepReconciliationPolicyTest {

    @Test
    fun `health connect must be more than five hundred steps ahead`() {
        assertTrue(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 5601,
                stressGuardSteps = 5100,
                stressGuardSource = DailyStepSource.HEALTH_CONNECT,
            )
        )
    }

    @Test
    fun `exactly five hundred steps ahead is not corrected`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 5600,
                stressGuardSteps = 5100,
                stressGuardSource = DailyStepSource.HEALTH_CONNECT,
            )
        )
    }

    @Test
    fun `health connect never lowers stressguard steps`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 4000,
                stressGuardSteps = 5600,
                stressGuardSource = DailyStepSource.HEALTH_CONNECT,
            )
        )
    }

    @Test
    fun `negative thresholds cannot force a correction`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 5100,
                stressGuardSteps = 5100,
                stressGuardSource = DailyStepSource.HEALTH_CONNECT,
                threshold = -1,
            )
        )
    }

    @Test
    fun `negative health connect totals are rejected`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = -1,
                stressGuardSteps = -1000,
                stressGuardSource = DailyStepSource.HEALTH_CONNECT,
            )
        )
    }

    @Test
    fun `an absent stressguard count can be corrected from health connect`() {
        assertTrue(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 501,
                stressGuardSteps = null,
                stressGuardSource = null,
            )
        )
    }

    @Test
    fun `health connect never replaces a watch owned count`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 10_000,
                stressGuardSteps = 5000,
                stressGuardSource = DailyStepSource.WATCH,
            )
        )
    }

    @Test
    fun `legacy count waits for a watch sample rather than being inflated again`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 10_000,
                stressGuardSteps = 5000,
                stressGuardSource = DailyStepSource.LEGACY,
            )
        )
    }
}
