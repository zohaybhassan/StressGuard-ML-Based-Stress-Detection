package com.example.stressguard

import kotlin.math.roundToInt

/**
 * Presentation rules shared by the phone dashboard and every Wear OS payload.
 *
 * Keeping the score conversion here is important: the model predicts class probabilities, not a
 * percentage. If the phone and watch each invent their own conversion they can show different
 * numbers for the same prediction, which is worse than showing no number at all.
 */
object StressDisplay {
    private const val GAUGE_MIN = 10f
    private const val GAUGE_MAX = 90f

    fun score(probabilities: FloatArray): Int {
        if (probabilities.size < 2) return 0
        val step = (GAUGE_MAX - GAUGE_MIN) / (probabilities.size - 1)
        return probabilities.withIndex()
            .sumOf { (index, probability) ->
                (probability * (GAUGE_MIN + step * index)).toDouble()
            }
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun label(rawLabel: String): String = when (rawLabel.lowercase()) {
        "relaxed_low_stress" -> "RELAXED"
        "normal", "not_stressed" -> "NORMAL"
        "stressed_high", "stressed" -> "HIGH STRESS"
        else -> rawLabel.replace('_', ' ').uppercase()
    }

    fun severity(classIndex: Int, classCount: Int): StressSeverity = when {
        classCount <= 0 -> StressSeverity.UNKNOWN
        classIndex >= classCount - 1 -> StressSeverity.HIGH
        classIndex == 0 -> StressSeverity.LOW
        else -> StressSeverity.MODERATE
    }
}

enum class StressSeverity {
    UNKNOWN,
    LOW,
    MODERATE,
    HIGH,
}
