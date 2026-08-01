package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.CredentialRules
import com.example.stressguard.data.SignInResult
import com.example.stressguard.data.SupabaseConfig
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Sign in or register, against Supabase.
 *
 * Email and password is the primary route because it is the only one that has a *register* step to
 * offer: Google sign-in silently creates an account on first use, which is convenient but gives a
 * user with no Google account nowhere to go. Google is kept as a second option rather than removed,
 * since accounts already exist behind it.
 *
 * One screen, two modes, rather than two Activities. The fields are the same and the difference is
 * a confirm-password box and which call is made, so a second screen would duplicate the layout, the
 * validation and the routing to buy nothing.
 *
 * The session Supabase issues is persisted and refreshed by its own client, so this screen is
 * reached once. [MainActivity] routes past it on every later launch until the user signs out.
 */
class LoginActivity : AppCompatActivity() {

    private enum class Mode { SIGN_IN, REGISTER }

    private var mode = Mode.SIGN_IN

    private lateinit var tvAuthTitle: TextView
    private lateinit var tvAuthSubtitle: TextView
    private lateinit var tvLoginStatus: TextView
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnPrimaryAuth: MaterialButton
    private lateinit var btnToggleAuthMode: MaterialButton
    private lateinit var btnGoogleSignIn: MaterialButton
    private lateinit var googleDivider: View
    private lateinit var tvGoogleHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        fitSystemBars(top = findViewById(R.id.loginRoot))
        bindViews()

        // Without a URL and key there is no backend at all, so nothing on this screen can work.
        val backendProblems = SupabaseConfig.backendProblems()
        if (backendProblems.isNotEmpty()) {
            showConfigurationProblems(backendProblems)
            return
        }

        // An existing Supabase session survives app restarts and is refreshed by the client, so a
        // returning user should not see this screen at all.
        if (AuthRepository.currentUser != null) {
            goToNextScreen()
            return
        }

        // A missing Google client ID disables only the Google button. Blocking the whole screen on
        // it, as this did before email sign-in existed, made an email-only project look broken.
        val googleUsable = SupabaseConfig.isGoogleConfigured
        btnGoogleSignIn.visibility = if (googleUsable) View.VISIBLE else View.GONE
        googleDivider.visibility = if (googleUsable) View.VISIBLE else View.GONE

        btnPrimaryAuth.setOnClickListener { submit() }
        btnToggleAuthMode.setOnClickListener { toggleMode() }
        btnGoogleSignIn.setOnClickListener { signInWithGoogle() }

        applyMode()
    }

    private fun bindViews() {
        tvAuthTitle = findViewById(R.id.tvAuthTitle)
        tvAuthSubtitle = findViewById(R.id.tvAuthSubtitle)
        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnPrimaryAuth = findViewById(R.id.btnPrimaryAuth)
        btnToggleAuthMode = findViewById(R.id.btnToggleAuthMode)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        googleDivider = findViewById(R.id.googleDivider)
        tvGoogleHint = findViewById(R.id.tvGoogleHint)
    }

    private fun toggleMode() {
        mode = if (mode == Mode.SIGN_IN) Mode.REGISTER else Mode.SIGN_IN
        clearErrors()
        tvLoginStatus.visibility = View.GONE
        applyMode()
    }

    /**
     * Registering is presented as a Google-first flow, signing in as an even choice.
     *
     * That asymmetry is the point of the design: signing up with Google and then setting a password
     * gives the account both routes, whereas signing up with an email gives it only one. Google is
     * therefore the recommended way in, and the email form stays as a fallback so a device with no
     * Google account is not locked out entirely.
     */
    private fun applyMode() = when (mode) {
        Mode.SIGN_IN -> {
            tvAuthTitle.text = "Sign in"
            tvAuthSubtitle.text = "Welcome back. Use your email and password, or Google."
            btnPrimaryAuth.text = "Sign in"
            btnToggleAuthMode.text = "New here? Create an account"
            tilConfirmPassword.visibility = View.GONE
            tvGoogleHint.visibility = View.GONE
        }

        Mode.REGISTER -> {
            tvAuthTitle.text = "Create an account"
            tvAuthSubtitle.text =
                "Signing up with Google is quickest — you will choose a password straight after, " +
                    "so you can use either from then on."
            btnPrimaryAuth.text = "Create account with email"
            btnToggleAuthMode.text = "Already have an account? Sign in"
            tilConfirmPassword.visibility = View.VISIBLE
            tvGoogleHint.visibility = if (SupabaseConfig.isGoogleConfigured) View.VISIBLE else View.GONE
        }
    }

    private fun submit() {
        clearErrors()

        val email = etEmail.text?.toString().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        val confirmation = etConfirmPassword.text?.toString().orEmpty()

        // Checked locally first so an obviously incomplete form does not cost a round trip, and so
        // the message lands on the field it is about rather than in the status line.
        var valid = true
        CredentialRules.emailProblem(email)?.let { tilEmail.error = it; valid = false }
        CredentialRules.passwordProblem(password)?.let { tilPassword.error = it; valid = false }
        if (mode == Mode.REGISTER) {
            CredentialRules.confirmationProblem(password, confirmation)
                ?.let { tilConfirmPassword.error = it; valid = false }
        }
        if (!valid) return

        setBusy(true, if (mode == Mode.REGISTER) "Creating your account…" else "Signing in…")
        lifecycleScope.launch {
            val result = when (mode) {
                Mode.SIGN_IN -> AuthRepository.signInWithEmail(email, password)
                Mode.REGISTER -> AuthRepository.signUpWithEmail(email, password)
            }
            handle(result)
        }
    }

    private fun signInWithGoogle() {
        clearErrors()
        setBusy(true, "Signing in…")
        lifecycleScope.launch { handle(AuthRepository.signInWithGoogle(this@LoginActivity)) }
    }

    private fun handle(result: SignInResult) {
        when (result) {
            is SignInResult.Success -> {
                Toast.makeText(
                    this,
                    "Signed in as ${result.user.email ?: "user"}",
                    Toast.LENGTH_SHORT,
                ).show()
                goToNextScreen()
            }

            // Nothing went wrong, but there is no session yet — so the user stays here, switched
            // back to sign-in, ready for when they have clicked the link.
            is SignInResult.ConfirmationEmailSent -> {
                setBusy(false)
                mode = Mode.SIGN_IN
                applyMode()
                etPassword.text = null
                etConfirmPassword.text = null
                showStatus(
                    "Account created. Check ${result.email} for a confirmation link, then sign in.",
                    isError = false,
                )
            }

            SignInResult.Cancelled -> {
                setBusy(false)
                tvLoginStatus.visibility = View.GONE
            }

            SignInResult.NoAccountAvailable -> {
                setBusy(false)
                showStatus(
                    "No Google account on this device. Add one in Settings, or use email instead.",
                    isError = true,
                )
            }

            is SignInResult.NotConfigured -> showConfigurationProblems(result.problems)

            is SignInResult.Failed -> {
                setBusy(false)
                showStatus(result.message, isError = true)
            }
        }
    }

    private fun clearErrors() {
        tilEmail.error = null
        tilPassword.error = null
        tilConfirmPassword.error = null
    }

    private fun showStatus(message: String, isError: Boolean) {
        tvLoginStatus.visibility = View.VISIBLE
        tvLoginStatus.text = message
        tvLoginStatus.setTextColor(
            if (isError) 0xFFB00020.toInt() else 0xFF2E7D32.toInt()
        )
    }

    /**
     * Configuration mistakes are the most likely reason sign-in fails on a fresh checkout, and they
     * otherwise surface as an unexplained network or OAuth error. Name them instead.
     */
    private fun showConfigurationProblems(problems: List<String>) {
        btnPrimaryAuth.isEnabled = false
        btnGoogleSignIn.isEnabled = false
        btnToggleAuthMode.isEnabled = false
        showStatus(
            buildString {
                append("Sign-in is not configured:\n")
                problems.forEach { append("• ").append(it).append('\n') }
                append("\nSee supabase.properties.template.")
            },
            isError = true,
        )
    }

    private fun setBusy(busy: Boolean, message: String = "") {
        btnPrimaryAuth.isEnabled = !busy
        btnToggleAuthMode.isEnabled = !busy
        btnGoogleSignIn.isEnabled = !busy
        if (busy) showStatus(message, isError = false)
    }

    /**
     * A fresh install has no stored session, so this screen — not MainActivity — is where a
     * reinstalling user lands. Both share [PostAuthRouter] so that the set-password step and the
     * profile recovery cannot be skipped depending on which door the user came through.
     */
    private fun goToNextScreen() {
        lifecycleScope.launch {
            showStatus("Setting things up…", isError = false)
            startActivity(Intent(this@LoginActivity, PostAuthRouter.nextScreen(this@LoginActivity)))
            finish()
        }
    }
}
