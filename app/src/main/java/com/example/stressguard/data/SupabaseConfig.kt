package com.example.stressguard.data

import com.example.stressguard.BuildConfig

/**
 * Backend configuration, supplied through local.properties at build time.
 * See supabase.properties.template at the repository root.
 *
 * Values are validated rather than assumed, because a missing one otherwise surfaces much
 * later as an opaque network or OAuth failure. [problems] gives something specific to show.
 */
object SupabaseConfig {

    val url: String = BuildConfig.SUPABASE_URL
    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** Human-readable reasons the backend cannot be reached, empty when configuration is complete. */
    fun problems(): List<String> = buildList {
        if (url.isBlank()) {
            add("supabase.url is not set in local.properties")
        } else if (!url.startsWith("https://")) {
            add("supabase.url must start with https://")
        }

        if (publishableKey.isBlank()) {
            add("supabase.publishableKey is not set in local.properties")
        } else if (publishableKey.startsWith("sb_secret_") || publishableKey.contains("service_role")) {
            // Worth failing loudly: a secret key in the app bypasses row-level security, so
            // every user would be able to read and delete every other user's data.
            add("supabase.publishableKey holds a SECRET key. Use the publishable key instead")
        }

        if (googleWebClientId.isBlank()) {
            add("supabase.googleWebClientId is not set in local.properties")
        } else if (!googleWebClientId.endsWith(".apps.googleusercontent.com")) {
            add("supabase.googleWebClientId does not look like a Google OAuth client ID")
        }
    }

    val isConfigured: Boolean get() = problems().isEmpty()

    /** Auth needs these two; the Google client ID is only required for Google sign-in. */
    val isBackendConfigured: Boolean
        get() = url.startsWith("https://") && publishableKey.isNotBlank()
}
