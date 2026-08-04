package com.example.stressguard.data

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
            )
        )
    }

    @Test
    fun `exactly five hundred steps ahead is not corrected`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 5600,
                stressGuardSteps = 5100,
            )
        )
    }

    @Test
    fun `health connect never lowers stressguard steps`() {
        assertFalse(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 4000,
                stressGuardSteps = 5600,
            )
        )
    }

    @Test
    fun `an absent stressguard count can be corrected from health connect`() {
        assertTrue(
            StepReconciliationPolicy.shouldCorrect(
                healthConnectSteps = 501,
                stressGuardSteps = null,
            )
        )
    }
}
