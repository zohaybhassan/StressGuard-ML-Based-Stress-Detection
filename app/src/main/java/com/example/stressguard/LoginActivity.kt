package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.SignInResult
import com.example.stressguard.data.SupabaseConfig
import kotlinx.coroutines.launch

/**
 * Google sign-in, exchanged for a Supabase session.
 *
 * Replaces the previous GoogleSignInClient flow, which authenticated against Google alone and
 * kept a local "signed in" boolean. That never produced a token any backend could verify, so
 * there was no identity to attach data to. The session now comes from Supabase and is what
 * row-level security keys off.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var tvLoginStatus: TextView
    private lateinit var btnGoogleSignIn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)

        val problems = SupabaseConfig.problems()
        if (problems.isNotEmpty()) {
            showConfigurationProblems(problems)
            return
        }

        // An existing Supabase session survives app restarts and is refreshed by the client,
        // so a returning user should not see this screen at all.
        if (AuthRepository.currentUser != null) {
            goToNextScreen()
            return
        }

        btnGoogleSignIn.setOnClickListener { signIn() }
    }

    private fun signIn() {
        setBusy(true)
        tvLoginStatus.text = ""

        lifecycleScope.launch {
            when (val result = AuthRepository.signInWithGoogle(this@LoginActivity)) {
                is SignInResult.Success -> {
                    Toast.makeText(
                        this@LoginActivity,
                        "Signed in as ${result.user.email ?: "user"}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    goToNextScreen()
                }

                SignInResult.Cancelled -> {
                    setBusy(false)
                    tvLoginStatus.text = ""
                }

                SignInResult.NoAccountAvailable -> {
                    setBusy(false)
                    tvLoginStatus.text =
                        "No Google account on this device. Add one in Settings, then try again."
                }

                is SignInResult.NotConfigured -> showConfigurationProblems(result.problems)

                is SignInResult.Failed -> {
                    setBusy(false)
                    tvLoginStatus.text = result.message
                }
            }
        }
    }

    /**
     * Configuration mistakes are the most likely reason sign-in fails on a fresh checkout, and
     * they otherwise surface as an unexplained OAuth error. Name them instead.
     */
    private fun showConfigurationProblems(problems: List<String>) {
        btnGoogleSignIn.isEnabled = false
        tvLoginStatus.text = buildString {
            append("Sign-in is not configured:\n")
            problems.forEach { append("• ").append(it).append('\n') }
            append("\nSee supabase.properties.template.")
        }
    }

    private fun setBusy(busy: Boolean) {
        btnGoogleSignIn.isEnabled = !busy
        tvLoginStatus.visibility = View.VISIBLE
        if (busy) tvLoginStatus.text = "Signing in…"
    }

    private fun goToNextScreen() {
        val nextScreen = if (SessionManager.isProfileComplete(this)) {
            HomeDashboardActivity::class.java
        } else {
            ProfileSetupActivity::class.java
        }
        startActivity(Intent(this, nextScreen))
        finish()
    }
}
