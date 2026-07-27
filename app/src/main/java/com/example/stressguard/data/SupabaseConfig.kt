package com.example.stressguard.data

import com.example.stressguard.BuildConfig
import java.util.Base64

/**
 * Backend configuration, supplied through local.properties at build time.
 * See supabase.properties.template at the repository root.
 *
 * Values are validated rather than assumed, because a missing or wrong one otherwise surfaces
 * much later as an opaque network or OAuth failure. [problems] gives something specific to show.
 */
object SupabaseConfig {

    val url: String = BuildConfig.SUPABASE_URL
    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** Every configuration problem, empty when configuration is complete. */
    fun problems(): List<String> = validate(url, publishableKey, googleWebClientId)

    /**
     * Problems that stop the app working at all.
     *
     * Separated from [googleProblems] because they have different consequences and the login
     * screen has to treat them differently. Without a URL and key there is no backend and nothing
     * can sign in; without a Google client ID only the Google button is unusable, and email and
     * password sign-in is unaffected. Blocking the whole screen on the latter — which is what
     * happened before email auth existed — makes a project configured for email-only look broken.
     */
    fun backendProblems(): List<String> = validate(url, publishableKey, VALID_CLIENT_ID_PLACEHOLDER)

    /** Problems that disable Google sign-in specifically, leaving email and password usable. */
    fun googleProblems(): List<String> = validate(VALID_URL_PLACEHOLDER, VALID_KEY_PLACEHOLDER, googleWebClientId)

    val isConfigured: Boolean get() = problems().isEmpty()

    /** Auth needs these two; the Google client ID is only required for Google sign-in. */
    val isBackendConfigured: Boolean
        get() = url.startsWith("https://") && publishableKey.isNotBlank() && !isSecretKey(publishableKey)

    /** Whether Google sign-in can be offered. Email and password does not depend on this. */
    val isGoogleConfigured: Boolean get() = googleProblems().isEmpty()

    // Stand-ins so each of the two checks above can reuse `validate` and report only its own
    // half. Never used as real configuration.
    private const val VALID_URL_PLACEHOLDER = "https://placeholder.supabase.co"
    private const val VALID_KEY_PLACEHOLDER = "sb_publishable_placeholder"
    private const val VALID_CLIENT_ID_PLACEHOLDER = "0.apps.googleusercontent.com"

    /** Pure, so the rules can be tested without a build configuration behind them. */
    fun validate(
        url: String,
        publishableKey: String,
        googleWebClientId: String,
    ): List<String> = buildList {
        if (url.isBlank()) {
            add("supabase.url is not set in local.properties")
        } else if (!url.startsWith("https://")) {
            add("supabase.url must start with https://")
        }

        if (publishableKey.isBlank()) {
            add("supabase.publishableKey is not set in local.properties")
        } else if (isSecretKey(publishableKey)) {
            // Worth failing loudly and refusing to run. A secret key bypasses row-level
            // security, so shipping one would let any user of the app read and delete every
            // other user's data. It is also readable by anyone who unzips the APK.
            add(
                "supabase.publishableKey holds a SECRET key. Revoke it immediately and use " +
                    "the publishable key instead"
            )
        }

        if (googleWebClientId.isBlank()) {
            add("supabase.googleWebClientId is not set in local.properties")
        } else if (!googleWebClientId.endsWith(".apps.googleusercontent.com")) {
            add("supabase.googleWebClientId does not look like a Google OAuth client ID")
        }
    }

    /**
     * Detects both key formats Supabase issues for privileged access.
     *
     * The newer form is recognisable by prefix. The legacy form is a JWT carrying
     * `"role":"service_role"` in its payload, which is base64url-encoded -- so a plain
     * substring search over the key text finds nothing and would wave it through.
     */
    fun isSecretKey(key: String): Boolean {
        if (key.startsWith("sb_secret_")) return true
        return jwtRole(key) == "service_role"
    }

    private fun jwtRole(key: String): String? {
        val parts = key.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val payload = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
            val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            ROLE_CLAIM.find(json)?.groupValues?.get(1)
        }.getOrNull()
    }

    private val ROLE_CLAIM = Regex("\"role\"\\s*:\\s*\"([^\"]+)\"")
}
