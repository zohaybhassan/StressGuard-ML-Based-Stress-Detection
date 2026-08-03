package com.example.stressguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutModeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `workout mode is active before its expiry`() {
        assertTrue(SessionManager.isWorkoutModeActive(now + 1, now))
    }

    @Test
    fun `workout mode expires exactly at its boundary`() {
        assertFalse(SessionManager.isWorkoutModeActive(now, now))
    }

    @Test
    fun `cleared or never-started workout mode is inactive`() {
        assertFalse(SessionManager.isWorkoutModeActive(0L, now))
    }

    @Test
    fun `past workout mode is inactive`() {
        assertFalse(SessionManager.isWorkoutModeActive(now - 1, now))
    }
}
