package com.example.stressguard.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyStepCounterTest {

    @Test
    fun `step delta measures progress from the baseline`() {
        assertEquals(750, DailyStepCounter.stepDelta(4_750L, 4_000L))
    }

    @Test
    fun `counter resets cannot produce negative steps`() {
        assertEquals(0, DailyStepCounter.stepDelta(100L, 4_000L))
    }

    @Test
    fun `very large counters cannot overflow the displayed total`() {
        assertEquals(Int.MAX_VALUE, DailyStepCounter.stepDelta(Long.MAX_VALUE, 0L))
    }
}
