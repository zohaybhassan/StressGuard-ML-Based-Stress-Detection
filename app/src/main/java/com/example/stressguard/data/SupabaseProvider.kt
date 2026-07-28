package com.example.stressguard.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
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
            install(Auth) {
                // These are the library defaults, set explicitly because they are the behaviour
                // the app promises: signing in once lasts until the user signs out. The session is
                // written to storage, reloaded on launch, and its access token refreshed in the
                // background before it expires. A future version changing a default would
                // otherwise silently start logging everyone out.
                autoSaveToStorage = true
                autoLoadFromStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            // The chatbot calls an Edge Function rather than Hugging Face directly, so the API
            // token stays on the server instead of being shipped inside an APK anyone can unzip.
            install(Functions)
        }
    }

    val auth get() = client.auth
}
