package com.example.stressguard

import android.content.Context

object StressFeatureBuilder {
    const val FEATURE_COUNT = 22

    fun build(
        context: Context,
        heartRate: Int,
        dailySteps: Int,
        sleepHours: Float,
    ): FloatArray? {
        val age = SessionManager.getUserAge(context) ?: return null
        val gender = SessionManager.getUserGender(context).orEmpty()
        val occupation = normalizeOccupation(SessionManager.getUserOccupation(context).orEmpty())
        val bmi = SessionManager.getUserBmi(context).orEmpty()

        return floatArrayOf(
            age.toFloat(),
            heartRate.toFloat(),
            dailySteps.toFloat(),
            sleepHours,
            if (gender.equals("Male", ignoreCase = true)) 1f else 0f,
            occupationFlag(occupation, "Artist"),
            occupationFlag(occupation, "Chef"),
            occupationFlag(occupation, "Doctor"),
            occupationFlag(occupation, "Engineer"),
            occupationFlag(occupation, "Lawyer"),
            occupationFlag(occupation, "Manager"),
            occupationFlag(occupation, "Nurse"),
            occupationFlag(occupation, "Sales Representative"),
            occupationFlag(occupation, "Salesperson"),
            occupationFlag(occupation, "Scientist"),
            occupationFlag(occupation, "Software Engineer"),
            occupationFlag(occupation, "Student"),
            occupationFlag(occupation, "Teacher"),
            occupationFlag(occupation, "Writer"),
            if (bmi.equals("Obese", ignoreCase = true)) 1f else 0f,
            if (bmi.equals("Overweight", ignoreCase = true)) 1f else 0f,
            if (bmi.equals("Underweight", ignoreCase = true)) 1f else 0f,
        )
    }

    private fun occupationFlag(actual: String, expected: String): Float =
        if (actual.equals(expected, ignoreCase = true)) 1f else 0f

    private fun normalizeOccupation(value: String): String =
        when {
            value.equals("Sales Rep", ignoreCase = true) -> "Sales Representative"
            value.equals("Sales Person", ignoreCase = true) -> "Salesperson"
            else -> value
        }
}
