package com.example.stressguard

import android.content.Context

object SessionManager {
    private const val PREF_NAME = "StressGuardSession"
    private const val KEY_IS_PROFILE_COMPLETE = "is_profile_complete"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_AGE = "user_age"
    private const val KEY_USER_GENDER = "user_gender"
    private const val KEY_USER_OCCUPATION = "user_occupation"
    private const val KEY_USER_BMI = "user_bmi"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setProfileComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PROFILE_COMPLETE, complete).apply()
    }

    fun isProfileComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PROFILE_COMPLETE, false)

    fun saveProfile(
        context: Context,
        name: String,
        age: Int,
        gender: String,
        occupation: String,
        bmi: String,
    ) {
        prefs(context).edit()
            .putString(KEY_USER_NAME, name)
            .putInt(KEY_USER_AGE, age)
            .putString(KEY_USER_GENDER, gender)
            .putString(KEY_USER_OCCUPATION, occupation)
            .putString(KEY_USER_BMI, bmi)
            .putBoolean(KEY_IS_PROFILE_COMPLETE, true)
            .apply()
    }

    /** The saved profile, or null when any field the model needs is missing. */
    fun readProfile(context: Context): StressProfile? {
        val age = getUserAge(context) ?: return null
        val gender = getUserGender(context) ?: return null
        val occupation = getUserOccupation(context) ?: return null
        val bmi = getUserBmi(context) ?: return null
        return StressProfile(age = age, gender = gender, occupation = occupation, bmi = bmi)
    }

    fun getUserName(context: Context): String? = prefs(context).getString(KEY_USER_NAME, null)

    fun getUserAge(context: Context): Int? {
        val value = prefs(context).getInt(KEY_USER_AGE, -1)
        return value.takeIf { it >= 0 }
    }

    fun getUserGender(context: Context): String? =
        prefs(context).getString(KEY_USER_GENDER, null)?.takeIf { it.isNotBlank() }

    fun getUserOccupation(context: Context): String? =
        prefs(context).getString(KEY_USER_OCCUPATION, null)?.takeIf { it.isNotBlank() }

    fun getUserBmi(context: Context): String? =
        prefs(context).getString(KEY_USER_BMI, null)?.takeIf { it.isNotBlank() }
}
