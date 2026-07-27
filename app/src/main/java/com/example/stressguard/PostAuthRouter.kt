package com.example.stressguard

import android.content.Context
import com.example.stressguard.data.ProfileRepository

/**
 * Where a signed-in user should go next.
 *
 * Shared because three places need the answer — the splash router, the login screen after a
 * successful sign-in, and the set-password screen once it is done — and they were already drifting
 * apart before this existed: only one of them recovered a profile after a reinstall, so the same
 * user got a different destination depending on which door they came through.
 */
object PostAuthRouter {

    /**
     * @return the Activity to start. Assumes there is a session; callers check that first.
     *
     * The order matters. Setting a password comes before the profile form because it is the step
     * that finishes creating the account: a Google sign-up has no password until then, and a user
     * who filled in their profile and then closed the app would be left with an account they could
     * only ever reach through Google.
     */
    suspend fun nextScreen(context: Context): Class<*> {
        if (!hasPassword(context)) return SetPasswordActivity::class.java

        // No local profile usually means a reinstall rather than a new user, so recover it from
        // the backend before asking them to fill the form in again.
        val hasProfile = ProfileRepository.ensureLocalProfile(context)
        return if (hasProfile) HomeDashboardActivity::class.java else ProfileSetupActivity::class.java
    }

    /**
     * Whether the account has a password, asking the server only until the answer is yes.
     *
     * Without the local cache this would be a network round trip on every single launch, for a fact
     * that changes once in the lifetime of an account. Only a positive answer is cached, so an
     * account that still needs a password is re-checked each time and cannot slip through on a
     * stale flag.
     */
    private suspend fun hasPassword(context: Context): Boolean {
        if (SessionManager.isPasswordKnownSet(context)) return true

        val hasOne = ProfileRepository.hasPassword()
        if (hasOne) SessionManager.markPasswordSet(context)
        return hasOne
    }
}
