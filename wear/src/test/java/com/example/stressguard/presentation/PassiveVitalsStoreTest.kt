package com.example.stressguard.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule deciding which step count the watch keeps.
 *
 * Regression cover for a bug that made the phone believe the wearer had taken no steps all day.
 * Two sources write this figure and they are not equally trustworthy: `DataType.STEPS_DAILY` is
 * the platform's own count since midnight, while `DailyStepCounter` subtracts a baseline it stored
 * itself and reports near zero after a reinstall or a reboot. A plain setter let the weaker one
 * land last and win — the logs showed a passive batch recording 4010 steps and the foreground path
 * writing 0 over it seconds later.
 */
class PassiveVitalsStoreTest {

    private val today = "2026-07-27"
    private val yesterday = "2026-07-26"

    @Test
    fun `a weaker source cannot lower today's count`() {
        // The exact failure: STEPS_DAILY had 4010, DailyStepCounter's baseline was gone.
        val kept = PassiveVitalsStore.resolveSteps(
            storedDate = today,
            storedSteps = 4010,
            incomingDate = today,
            incomingSteps = 0,
        )

        assertEquals(4010, kept)
    }

    @Test
    fun `a genuine increase during the day is taken`() {
        val kept = PassiveVitalsStore.resolveSteps(
            storedDate = today,
            storedSteps = 4010,
            incomingDate = today,
            incomingSteps = 4700,
        )

        assertEquals(4700, kept)
    }

    @Test
    fun `a new day replaces yesterday's total rather than keeping the maximum`() {
        // Taking the maximum across days would leave a high Tuesday standing in for every
        // quieter day after it, and the count would never fall again.
        val kept = PassiveVitalsStore.resolveSteps(
            storedDate = yesterday,
            storedSteps = 12_000,
            incomingDate = today,
            incomingSteps = 300,
        )

        assertEquals(300, kept)
    }

    @Test
    fun `a midnight reset to zero is accepted as the new day's starting point`() {
        val kept = PassiveVitalsStore.resolveSteps(
            storedDate = yesterday,
            storedSteps = 9000,
            incomingDate = today,
            incomingSteps = 0,
        )

        assertEquals(0, kept)
    }

    @Test
    fun `the first count ever recorded is taken as-is`() {
        val kept = PassiveVitalsStore.resolveSteps(
            storedDate = null,
            storedSteps = 0,
            incomingDate = today,
            incomingSteps = 4010,
        )

        assertEquals(4010, kept)
    }
}
