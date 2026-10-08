package com.example.stressguard

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeThemeManagerTest {
    @Test
    fun daylightRunsFromSixUntilSix() {
        assertEquals(ThemePeriod.NIGHT, TimeThemeManager.periodForHour(5))
        assertEquals(ThemePeriod.DAY, TimeThemeManager.periodForHour(6))
        assertEquals(ThemePeriod.DAY, TimeThemeManager.periodForHour(17))
        assertEquals(ThemePeriod.NIGHT, TimeThemeManager.periodForHour(18))
    }

    @Test
    fun greetingCopyTracksMorningAfternoonAndNight() {
        assertEquals(GreetingPeriod.MORNING, TimeThemeManager.greetingForHour(8))
        assertEquals(GreetingPeriod.AFTERNOON, TimeThemeManager.greetingForHour(15))
        assertEquals(GreetingPeriod.NIGHT, TimeThemeManager.greetingForHour(22))
    }
}
