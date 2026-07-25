package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.ProfileRepository
import com.example.stressguard.data.SupabaseConfig
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Splash router.
 *
 * Waits for the Supabase client to finish restoring a stored session before deciding where to
 * go. Reading the session synchronously here would send a signed-in user to the login screen,
 * because restoring from storage (and refreshing an expired token) has not finished yet.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Without configuration there is no session to wait for; let LoginActivity explain.
        if (!SupabaseConfig.isBackendConfigured) {
            route(LoginActivity::class.java)
            return
        }

        lifecycleScope.launch {
            val signedIn = AuthRepository.sessionStatus
                .first { it !is SessionStatus.Initializing } is SessionStatus.Authenticated

            if (!signedIn) {
                route(LoginActivity::class.java)
                return@launch
            }

            // No local profile can mean a reinstall or a new device rather than a new user, so
            // try to recover it before asking again. Bounded: with no network the user just
            // fills the form, which is the same outcome as before.
            var hasProfile = SessionManager.isProfileComplete(this@MainActivity)
            if (!hasProfile) {
                hasProfile = withTimeoutOrNull(PROFILE_PULL_TIMEOUT_MS) {
                    ProfileRepository.pull(this@MainActivity)
                } ?: false
            }

            route(
                if (hasProfile) HomeDashboardActivity::class.java
                else ProfileSetupActivity::class.java
            )
        }
    }

    private fun route(destination: Class<*>) {
        startActivity(Intent(this, destination))
        finish()
    }

    companion object {
        private const val PROFILE_PULL_TIMEOUT_MS = 4_000L
    }
}
