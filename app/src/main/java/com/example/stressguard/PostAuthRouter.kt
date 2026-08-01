package com.example.stressguard

import android.content.Context
import android.util.Log
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.LocalUserData
import com.example.stressguard.data.ProfileRepository
import com.example.stressguard.data.PredictionHistoryRepository

/**
 * Where a signed-in user should go next.
 *
 * Shared because three places need the answer — the splash router, the login screen after a
 * successful sign-in, and the set-password screen once it is done — and they were already drifting
 * apart before this existed: only one of them recovered a profile after a reinstall, so the same
 * user got a different destination depending on which door they came through.
 */
object PostAuthRouter {

    private const val TAG = "AUTH"

    /**
     * @return the Activity to start. Assumes there is a session; callers check that first.
     *
     * The order matters. Setting a password comes before the profile form because it is the step
     * that finishes creating the account: a Google sign-up has no password until then, and a user
     * who filled in their profile and then closed the app would be left with an account they could
     * only ever reach through Google.
     */
    suspend fun nextScreen(context: Context): Class<*> {
        discardAnyPreviousUsersData(context)

        if (!hasPassword(context)) return SetPasswordActivity::class.java

        // No local profile usually means a reinstall rather than a new user, so recover it from
        // the backend before asking them to fill the form in again.
        val hasProfile = ProfileRepository.ensureLocalProfile(context)
        if (hasProfile) PredictionHistoryRepository.ensureRecentLocal(context)
        return if (hasProfile) HomeDashboardActivity::class.java else ProfileSetupActivity::class.java
    }

    /**
     * Clears local data if the signed-in account is not the one it belongs to.
     *
     * Sign-out already clears it, so in the ordinary case this finds nothing to do. It exists for
     * the cases where sign-out never ran:
     *
     *  - the Supabase client drops a session on its own when a refresh token is revoked or replayed,
     *    which this app's logs have shown happening;
     *  - a sign-out whose database wipe failed;
     *  - anything added later that authenticates without going through the sign-out path.
     *
     * Checking identity rather than trusting the sign-out path makes the failure impossible instead
     * of merely fixed — the local store is not keyed by user, so this is the only thing standing
     * between one person's stress history and health checklist and the next person to sign in.
     *
     * Runs before everything else here, so nothing reads or writes local state belonging to the
     * wrong account first.
     */
    private suspend fun discardAnyPreviousUsersData(context: Context) {
        val userId = AuthRepository.currentUser?.id ?: return
        val previous = SessionManager.getLastUserId(context)

        if (previous != null && previous != userId) {
            Log.w(TAG, "signed in as a different account; discarding the previous user's local data")
            LocalUserData.clear(context)
        }

        // Written after the clear, which wipes this key along with everything else.
        SessionManager.setLastUserId(context, userId)
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
