package com.example.stressguard.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import com.example.stressguard.SessionManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
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

    /**
     * The account was created but Supabase issued no session, because the project has "Confirm
     * email" switched on and is waiting for the user to click the link it sent.
     *
     * A distinct outcome rather than an error: nothing went wrong, but the user is *not* signed in
     * and telling them otherwise would leave them staring at a login screen that just said
     * "success". Turn the setting off in Authentication → Sign In / Providers → Email to have
     * signup log the user straight in.
     */
    data class ConfirmationEmailSent(val email: String) : SignInResult

    data class Failed(val message: String, val cause: Throwable? = null) : SignInResult
}

/** Local checks, so an obviously bad form is refused without a network round trip. */
object CredentialRules {

    /** Supabase's own minimum. Rejecting it here gives a clearer message than a 422 does. */
    const val MIN_PASSWORD_LENGTH = 6

    fun emailProblem(email: String): String? = when {
        email.isBlank() -> "Enter your email address"
        !EMAIL.matches(email.trim()) -> "That does not look like an email address"
        else -> null
    }

    fun passwordProblem(password: String): String? = when {
        password.isEmpty() -> "Enter a password"
        password.length < MIN_PASSWORD_LENGTH ->
            "Use at least $MIN_PASSWORD_LENGTH characters"
        else -> null
    }

    /** Only checked when registering; signing in has no second field to compare against. */
    fun confirmationProblem(password: String, confirmation: String): String? =
        if (password != confirmation) "Passwords do not match" else null

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
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

    /**
     * Signs in with an email address and password.
     *
     * Deliberately does not distinguish "no such account" from "wrong password" in what it shows
     * the user: Supabase returns the same error for both, and reporting them separately would let
     * anyone use the login form to discover whether a given address has an account here.
     */
    suspend fun signInWithEmail(email: String, password: String): SignInResult {
        backendProblemOrNull()?.let { return it }
        val address = email.trim()

        return try {
            SupabaseProvider.auth.signInWith(Email) {
                this.email = address
                this.password = password
            }
            val user = SupabaseProvider.auth.currentUserOrNull()
                ?: return SignInResult.Failed("Signed in but no user was returned")
            Log.i(TAG, "signed in as ${user.email ?: user.id}")
            SignInResult.Success(user)
        } catch (error: Exception) {
            Log.w(TAG, "email sign-in refused", error)
            SignInResult.Failed(describeEmailFailure(error, signingUp = false), error)
        }
    }

    /**
     * Creates an account.
     *
     * Whether this also signs the user in depends on the Supabase project: with "Confirm email"
     * enabled — the default — no session is issued until the emailed link is clicked, and the
     * result is [SignInResult.ConfirmationEmailSent] rather than a success.
     */
    suspend fun signUpWithEmail(email: String, password: String): SignInResult {
        backendProblemOrNull()?.let { return it }
        val address = email.trim()

        return try {
            SupabaseProvider.auth.signUpWith(Email) {
                this.email = address
                this.password = password
            }

            // signUpWith returns a user either way; only a session means they are actually in.
            val user = SupabaseProvider.auth.currentUserOrNull()
            if (user == null) {
                Log.i(TAG, "account created for $address, awaiting email confirmation")
                SignInResult.ConfirmationEmailSent(address)
            } else {
                Log.i(TAG, "registered and signed in as ${user.email ?: user.id}")
                SignInResult.Success(user)
            }
        } catch (error: Exception) {
            Log.w(TAG, "sign-up refused", error)
            SignInResult.Failed(describeEmailFailure(error, signingUp = true), error)
        }
    }

    /**
     * Turns Supabase's error text into something a user can act on.
     *
     * The raw messages are HTTP-shaped ("Bad Request", a 422 body) and mean nothing on a phone
     * screen, so the common cases are named. Anything unrecognised falls through with its own
     * message rather than a generic one, because an unexpected failure that says only "try again"
     * is impossible to debug from a bug report.
     */
    private fun describeEmailFailure(error: Exception, signingUp: Boolean): String {
        val text = (error.message ?: "").lowercase()
        return when {
            text.contains("already registered") || text.contains("already been registered") ||
                text.contains("user already exists") ->
                "That email already has an account. Try signing in instead."

            text.contains("invalid login credentials") || text.contains("invalid_credentials") ->
                "Email or password is incorrect."

            text.contains("email not confirmed") ->
                "Check your inbox and confirm your email address first."

            text.contains("password") && text.contains("least") ->
                "Password must be at least ${CredentialRules.MIN_PASSWORD_LENGTH} characters."

            text.contains("rate limit") || text.contains("too many") ->
                "Too many attempts. Wait a minute and try again."

            text.contains("unable to resolve host") || text.contains("timeout") ||
                text.contains("failed to connect") ->
                "No connection. Check your network and try again."

            else -> {
                val action = if (signingUp) "create the account" else "sign in"
                "Could not $action: ${error.message ?: "unknown error"}"
            }
        }
    }

    /** [SignInResult.NotConfigured] when there is no usable backend, or null when there is. */
    private fun backendProblemOrNull(): SignInResult? {
        val problems = SupabaseConfig.backendProblems()
        if (problems.isEmpty()) return null
        Log.w(TAG, "auth blocked, backend not configured: $problems")
        return SignInResult.NotConfigured(problems)
    }

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

    /**
     * Gives the signed-in account a password.
     *
     * Needed because a Google sign-up creates a Supabase user with an empty `encrypted_password`,
     * so signing in with that email and any password would fail forever. Setting one here is what
     * makes the second sign-in route work at all.
     *
     * Requires a live session, which is why it runs immediately after Google sign-in rather than
     * being offered later from a settings screen.
     *
     * @return null on success, or a message to show the user.
     */
    suspend fun setPassword(context: Context, password: String): String? {
        if (currentUser == null) return "You are not signed in."

        return try {
            SupabaseProvider.auth.updateUser { this.password = password }
            // Recorded server-side so the app stops asking on every device, not just this one.
            ProfileRepository.markPasswordSet()
            SessionManager.markPasswordSet(context)
            Log.i(TAG, "password set for ${currentUser?.email ?: currentUser?.id}")
            null
        } catch (error: Exception) {
            Log.w(TAG, "could not set the password", error)
            describeEmailFailure(error, signingUp = true)
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
