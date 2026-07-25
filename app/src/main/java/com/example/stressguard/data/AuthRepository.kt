package com.example.stressguard.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface SignInResult {
    data class Success(val user: UserInfo) : SignInResult

    /** The user dismissed the Google sheet. Not an error; show nothing. */
    data object Cancelled : SignInResult

    /** No Google account usable on this device. */
    data object NoAccountAvailable : SignInResult

    data class NotConfigured(val problems: List<String>) : SignInResult

    data class Failed(val message: String, val cause: Throwable? = null) : SignInResult
}

/**
 * Google sign-in through Credential Manager, exchanged for a Supabase session.
 *
 * The flow is:
 *   1. Credential Manager returns a Google ID token, minted for the *web* client ID.
 *   2. That token goes to Supabase, which verifies it with Google and issues its own session.
 *
 * The web client ID rather than the Android one is deliberate and is the usual stumbling
 * block: Supabase validates that the token's audience matches the client ID configured on its
 * Google provider, which is the web client. The Android OAuth client still has to exist in
 * Google Cloud, registered against this app's package name and signing certificate, or Google
 * refuses to issue a token at all -- but its ID is never referenced in code.
 */
object AuthRepository {

    private const val TAG = "AUTH"

    val sessionStatus: Flow<SessionStatus> get() = SupabaseProvider.auth.sessionStatus

    val currentUser: UserInfo? get() = SupabaseProvider.auth.currentUserOrNull()

    /**
     * Name as supplied by the identity provider, for prefilling the profile form.
     * Google puts it in user metadata under full_name, occasionally name.
     */
    val displayName: String?
        get() = currentUser?.userMetadata?.let { metadata ->
            listOf("full_name", "name")
                .firstNotNullOfOrNull { key -> metadata[key]?.jsonPrimitive?.contentOrNull }
                ?.takeIf { it.isNotBlank() }
        }

    val email: String? get() = currentUser?.email?.takeIf { it.isNotBlank() }

    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val problems = SupabaseConfig.problems()
        if (problems.isNotEmpty()) {
            Log.w(TAG, "sign-in blocked, configuration incomplete: $problems")
            return SignInResult.NotConfigured(problems)
        }

        // Google hashes the nonce it embeds in the token; Supabase re-hashes the raw value to
        // compare. So Google gets the digest and Supabase gets the original.
        val rawNonce = generateNonce()
        val hashedNonce = sha256(rawNonce)

        val idToken = when (val result = requestGoogleIdToken(activityContext, hashedNonce)) {
            is TokenResult.Token -> result.value
            is TokenResult.Problem -> return result.asSignInResult
        }

        return try {
            SupabaseProvider.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
                this.nonce = rawNonce
            }
            val user = SupabaseProvider.auth.currentUserOrNull()
                ?: return SignInResult.Failed("Supabase accepted the token but returned no user")
            Log.i(TAG, "signed in as ${user.email ?: user.id}")
            SignInResult.Success(user)
        } catch (error: Exception) {
            Log.e(TAG, "Supabase rejected the Google ID token", error)
            SignInResult.Failed(
                "Could not complete sign-in with Supabase. Check that the Google provider is " +
                    "enabled and its client ID matches supabase.googleWebClientId.",
                error,
            )
        }
    }

    suspend fun signOut() {
        runCatching { SupabaseProvider.auth.signOut() }
            .onFailure { Log.w(TAG, "sign-out failed; clearing local session anyway", it) }
    }

    private sealed interface TokenResult {
        data class Token(val value: String) : TokenResult
        data class Problem(val asSignInResult: SignInResult) : TokenResult
    }

    private suspend fun requestGoogleIdToken(context: Context, hashedNonce: String): TokenResult {
        val credentialManager = CredentialManager.create(context)

        // Ask for an already-authorized account first, which gives returning users a single
        // tap. If there is none, widen to every account on the device.
        for (filterByAuthorized in listOf(true, false)) {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterByAuthorized)
                .setServerClientId(SupabaseConfig.googleWebClientId)
                .setNonce(hashedNonce)
                .build()

            try {
                val response = credentialManager.getCredential(
                    context = context,
                    request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
                )
                val credential = response.credential
                if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    return TokenResult.Problem(
                        SignInResult.Failed("Unexpected credential type ${credential.type}")
                    )
                }
                return TokenResult.Token(
                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                )
            } catch (cancelled: GetCredentialCancellationException) {
                Log.d(TAG, "user dismissed the Google sheet", cancelled)
                return TokenResult.Problem(SignInResult.Cancelled)
            } catch (none: NoCredentialException) {
                // Expected on the first pass when nothing has been authorized yet; retry unfiltered.
                if (filterByAuthorized) continue
                Log.w(TAG, "no Google account available on this device", none)
                return TokenResult.Problem(SignInResult.NoAccountAvailable)
            } catch (error: GetCredentialException) {
                Log.e(TAG, "Credential Manager could not return a Google ID token", error)
                return TokenResult.Problem(
                    SignInResult.Failed(
                        "Google sign-in is not set up for this build. Check that an Android " +
                            "OAuth client exists for this package and signing certificate.",
                        error,
                    )
                )
            }
        }
        return TokenResult.Problem(SignInResult.NoAccountAvailable)
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
