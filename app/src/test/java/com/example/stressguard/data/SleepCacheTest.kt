package com.example.stressguard.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepCacheTest {

    @Test
    fun `finite sleep durations within one day are accepted`() {
        assertTrue(isValidSleepHours(0f))
        assertTrue(isValidSleepHours(7.5f))
        assertTrue(isValidSleepHours(24f))
    }

    @Test
    fun `invalid sleep durations are rejected`() {
        assertFalse(isValidSleepHours(-0.1f))
        assertFalse(isValidSleepHours(24.1f))
        assertFalse(isValidSleepHours(Float.NaN))
        assertFalse(isValidSleepHours(Float.POSITIVE_INFINITY))
    }
}
