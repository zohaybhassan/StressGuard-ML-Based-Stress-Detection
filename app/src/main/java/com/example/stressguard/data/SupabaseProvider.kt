package com.example.stressguard.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * The single Supabase client for the app.
 *
 * Created lazily so that a build with no backend configuration still starts: the login screen
 * reports what is missing instead of the process dying at first touch.
 *
 * The client holds the session and refreshes it in the background, so the app should ask it
 * for auth state rather than caching a "signed in" flag of its own.
 */
object SupabaseProvider {

    val client: SupabaseClient by lazy {
        check(SupabaseConfig.isBackendConfigured) {
            "Supabase is not configured: ${SupabaseConfig.problems().joinToString("; ")}"
        }
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.publishableKey,
        ) {
            install(Auth)
            install(Postgrest)
        }
    }

    val auth get() = client.auth
}
