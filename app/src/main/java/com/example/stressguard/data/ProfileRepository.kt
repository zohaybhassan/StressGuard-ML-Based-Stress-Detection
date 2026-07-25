package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.SessionManager
import com.example.stressguard.StressProfile
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of public.profiles. Nullable because the signup trigger creates the row before the
 *  user has filled anything in. */
@Serializable
data class ProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val occupation: String? = null,
    @SerialName("bmi_category") val bmiCategory: String? = null,
) {
    /** Whether this row carries everything the stress model needs. */
    val isComplete: Boolean
        get() = age != null && !gender.isNullOrBlank() &&
            !occupation.isNullOrBlank() && !bmiCategory.isNullOrBlank()

    fun toStressProfile(): StressProfile? = if (!isComplete) null else StressProfile(
        age = age!!,
        gender = gender!!,
        occupation = occupation!!,
        bmi = bmiCategory!!,
    )
}

/**
 * Keeps the profile in two places, deliberately.
 *
 * Local storage is the source of truth for inference, because stress detection must keep
 * working with no network -- that is the project's central claim, and a profile that lives
 * only in Supabase would mean a cold start offline cannot even build a feature vector.
 *
 * Supabase is the durable copy: it survives reinstalling the app and moving to a new device,
 * and it is what the rest of the backend will key off.
 *
 * Sync is one-directional per event rather than a general reconciliation: a save pushes up,
 * and a device with no local profile pulls down. There is no merge, because the profile is
 * only ever edited on one device at a time in practice; last write wins.
 */
object ProfileRepository {

    private const val TABLE = "profiles"
    private const val TAG = "PROFILE_SYNC"
    private const val PULL_TIMEOUT_MS = 4_000L

    /**
     * Sends the locally saved profile to Supabase.
     *
     * Failure is not propagated to the user: the local save has already happened, so the app
     * is fully functional and a later launch can retry. Returns whether it landed.
     */
    suspend fun push(context: Context): Boolean {
        val userId = AuthRepository.currentUser?.id
        if (userId == null) {
            Log.w(TAG, "not signed in; keeping the profile local only")
            return false
        }
        val profile = SessionManager.readProfile(context) ?: return false

        return try {
            SupabaseProvider.client.from(TABLE).upsert(
                ProfileRow(
                    id = userId,
                    displayName = SessionManager.getUserName(context),
                    age = profile.age,
                    gender = profile.gender,
                    occupation = profile.occupation,
                    bmiCategory = profile.bmi,
                )
            )
            Log.i(TAG, "profile pushed for $userId")
            true
        } catch (error: Exception) {
            Log.w(TAG, "profile push failed; local copy is unaffected", error)
            false
        }
    }

    /**
     * Whether a usable profile is available locally, recovering it from Supabase if not.
     *
     * Every post-authentication route must go through this rather than reading the local flag
     * directly. Checking the flag alone sends a reinstalled user to the setup form even though
     * their profile is sitting in the database -- which is exactly what happened when only one
     * of the two entry points did the recovery.
     *
     * Time-bounded: with no network the user fills in the form, the same outcome as before.
     */
    suspend fun ensureLocalProfile(context: Context, timeoutMs: Long = PULL_TIMEOUT_MS): Boolean {
        if (SessionManager.isProfileComplete(context)) return true
        return withTimeoutOrNull(timeoutMs) { pull(context) } ?: false
    }

    /**
     * Fetches the profile for the signed-in user and caches it locally.
     *
     * Returns true only when a *complete* profile was found and cached, which is the signal
     * that profile setup can be skipped. The signup trigger creates an empty row immediately,
     * so merely finding a row means nothing.
     */
    suspend fun pull(context: Context): Boolean {
        val userId = AuthRepository.currentUser?.id ?: return false

        val row = try {
            SupabaseProvider.client.from(TABLE)
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<ProfileRow>()
        } catch (error: Exception) {
            Log.w(TAG, "profile pull failed; falling back to whatever is stored locally", error)
            return false
        }

        val profile = row?.toStressProfile()
        if (profile == null) {
            Log.d(TAG, "no complete profile stored for $userId")
            return false
        }

        SessionManager.saveProfile(
            context = context,
            name = row.displayName ?: AuthRepository.displayName.orEmpty(),
            age = profile.age,
            gender = profile.gender,
            occupation = profile.occupation,
            bmi = profile.bmi,
        )
        Log.i(TAG, "profile restored from Supabase for $userId")
        return true
    }
}
