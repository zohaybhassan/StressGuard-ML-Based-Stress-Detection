package com.example.stressguard

import android.content.Context

object SessionManager {
    private const val PREF_NAME = "StressGuardSession"
    private const val KEY_IS_SIGNED_IN = "is_signed_in"
    private const val KEY_IS_PROFILE_COMPLETE = "is_profile_complete"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_AGE = "user_age"
    private const val KEY_USER_GENDER = "user_gender"
    private const val KEY_USER_OCCUPATION = "user_occupation"
    private const val KEY_USER_BMI = "user_bmi"
    private const val KEY_GOOGLE_DISPLAY_NAME = "google_display_name"
    private const val KEY_GOOGLE_EMAIL = "google_email"
    private const val KEY_GOOGLE_PHOTO_URL = "google_photo_url"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setSignedIn(context: Context, signedIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_SIGNED_IN, signedIn).apply()
    }

    fun isSignedIn(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_SIGNED_IN, false)

    fun setProfileComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PROFILE_COMPLETE, complete).apply()
    }

    fun isProfileComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PROFILE_COMPLETE, false)

    fun saveGoogleAccount(
        context: Context,
        displayName: String?,
        email: String?,
        photoUrl: String?,
    ) {
        prefs(context).edit()
            .putString(KEY_GOOGLE_DISPLAY_NAME, displayName.orEmpty())
            .putString(KEY_GOOGLE_EMAIL, email.orEmpty())
            .putString(KEY_GOOGLE_PHOTO_URL, photoUrl.orEmpty())
            .apply()
    }

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

    fun getGoogleDisplayName(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }

    fun getGoogleEmail(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun getGooglePhotoUrl(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_PHOTO_URL, null)?.takeIf { it.isNotBlank() }
}
