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
    private const val KEY_PASSWORD_SET = "password_set"
    private const val KEY_LAST_USER_ID = "last_user_id"

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

    /**
     * Which account this device's local data belongs to.
     *
     * Read on every sign-in to catch a change of user. Sign-out clears local data, but a session can
     * also end *without* that path running — a revoked or already-used refresh token makes the
     * Supabase client drop the session on its own, which has been observed in this app's logs. The
     * next person to sign in would then inherit the previous user's history, so the identity is
     * checked rather than assumed.
     */
    fun getLastUserId(context: Context): String? =
        prefs(context).getString(KEY_LAST_USER_ID, null)?.takeIf { it.isNotBlank() }

    fun setLastUserId(context: Context, userId: String) {
        prefs(context).edit().putString(KEY_LAST_USER_ID, userId).apply()
    }

    /**
     * Remembers that this account has a password, so the router does not ask the server again.
     *
     * Cache-once rather than cache-with-expiry, because the fact only ever moves in one direction:
     * an account that has a password cannot stop having one. Only ever written as true — a false
     * answer is not cached, so an account still waiting to set one is re-checked on each launch and
     * cannot be let through by a stale local flag. Cleared on sign-out with everything else.
     */
    fun markPasswordSet(context: Context) {
        prefs(context).edit().putBoolean(KEY_PASSWORD_SET, true).apply()
    }

    fun isPasswordKnownSet(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PASSWORD_SET, false)

    /**
     * Forgets the stored profile, on sign-out.
     *
     * Nothing here is keyed by user, so a profile left behind becomes the next signed-in user's:
     * their predictions would be built from the previous person's age, gender, occupation and BMI,
     * and occupation alone moves the model's output more than the live vitals do.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
