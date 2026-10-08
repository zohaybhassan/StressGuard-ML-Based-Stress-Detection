package com.example.stressguard

import androidx.appcompat.app.AppCompatDelegate
import java.time.LocalTime

enum class ThemePeriod { DAY, NIGHT }
enum class GreetingPeriod { MORNING, AFTERNOON, NIGHT }

/** Selects a calm daylight or nighttime visual system from the device's local time. */
object TimeThemeManager {
    private const val DAY_START_HOUR = 6
    private const val NIGHT_START_HOUR = 18

    private var debugPreview: ThemePeriod? = null

    fun periodForHour(hour: Int): ThemePeriod {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        return if (hour in DAY_START_HOUR until NIGHT_START_HOUR) {
            ThemePeriod.DAY
        } else {
            ThemePeriod.NIGHT
        }
    }

    fun greetingForHour(hour: Int): GreetingPeriod {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        return when (hour) {
            in DAY_START_HOUR until 12 -> GreetingPeriod.MORNING
            in 12 until NIGHT_START_HOUR -> GreetingPeriod.AFTERNOON
            else -> GreetingPeriod.NIGHT
        }
    }

    fun currentPeriod(): ThemePeriod = debugPreview ?: periodForHour(LocalTime.now().hour)

    fun currentGreeting(): GreetingPeriod = when (debugPreview) {
        ThemePeriod.DAY -> greetingForHour(LocalTime.now().hour.coerceIn(DAY_START_HOUR, 17))
        ThemePeriod.NIGHT -> GreetingPeriod.NIGHT
        null -> greetingForHour(LocalTime.now().hour)
    }

    fun applyCurrentTheme() {
        val mode = when (currentPeriod()) {
            ThemePeriod.DAY -> AppCompatDelegate.MODE_NIGHT_NO
            ThemePeriod.NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    /** Debug builds use this through an ADB launch extra to capture both visual variants. */
    fun setDebugPreview(value: String?) {
        debugPreview = when (value?.lowercase()) {
            "day" -> ThemePeriod.DAY
            "night" -> ThemePeriod.NIGHT
            else -> null
        }
    }
}
