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

    /** Human-readable reasons the backend cannot be reached, empty when configuration is complete. */
    fun problems(): List<String> = validate(url, publishableKey, googleWebClientId)

    val isConfigured: Boolean get() = problems().isEmpty()

    /** Auth needs these two; the Google client ID is only required for Google sign-in. */
    val isBackendConfigured: Boolean
        get() = url.startsWith("https://") && publishableKey.isNotBlank() && !isSecretKey(publishableKey)

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
