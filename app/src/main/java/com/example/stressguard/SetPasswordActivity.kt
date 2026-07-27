package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.CredentialRules
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Gives a Google-created account a password, so email sign-in works next time.
 *
 * This step exists because of a Supabase behaviour that is easy to miss: an account created through
 * Google OAuth has an *empty* `encrypted_password`, so signing in with that same email and any
 * password fails forever with "Invalid login credentials". Offering both routes on the login screen
 * without this step would mean the email half never worked for anyone who signed up with Google.
 *
 * Not skippable, and back does not leave. A half-created account — signed in, no password — is the
 * state this screen exists to resolve, and letting the user past it would leave them with an
 * account they can only ever reach through Google, which is exactly what the flow is meant to
 * avoid. [PostAuthRouter] sends them back here on the next launch anyway, so a skip button would
 * only make the loop confusing rather than escapable.
 */
class SetPasswordActivity : AppCompatActivity() {

    private lateinit var tvEmail: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirm: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_password)

        tvEmail = findViewById(R.id.tvSetPasswordEmail)
        tvStatus = findViewById(R.id.tvSetPasswordStatus)
        tilPassword = findViewById(R.id.tilNewPassword)
        tilConfirm = findViewById(R.id.tilConfirmNewPassword)
        etPassword = findViewById(R.id.etNewPassword)
        etConfirm = findViewById(R.id.etConfirmNewPassword)
        btnSave = findViewById(R.id.btnSavePassword)

        // Shown because the password is paired with *this* address, and someone with several Google
        // accounts has no other way to tell which one they just signed in with.
        tvEmail.text = AuthRepository.email ?: "Your account"

        btnSave.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@SetPasswordActivity,
                    "Choose a password to finish setting up your account",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        })
    }

    private fun save() {
        tilPassword.error = null
        tilConfirm.error = null
        tvStatus.visibility = View.GONE

        val password = etPassword.text?.toString().orEmpty()
        val confirmation = etConfirm.text?.toString().orEmpty()

        var valid = true
        CredentialRules.passwordProblem(password)?.let { tilPassword.error = it; valid = false }
        CredentialRules.confirmationProblem(password, confirmation)
            ?.let { tilConfirm.error = it; valid = false }
        if (!valid) return

        btnSave.isEnabled = false
        showStatus("Saving…", isError = false)

        lifecycleScope.launch {
            val problem = AuthRepository.setPassword(this@SetPasswordActivity, password)
            if (problem != null) {
                btnSave.isEnabled = true
                showStatus(problem, isError = true)
                return@launch
            }

            Toast.makeText(
                this@SetPasswordActivity,
                "Password saved. You can now sign in with your email too.",
                Toast.LENGTH_SHORT,
            ).show()
            goToNextScreen()
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = message
        tvStatus.setTextColor(if (isError) 0xFFB00020.toInt() else 0xFF2E7D32.toInt())
    }

    private suspend fun goToNextScreen() {
        startActivity(Intent(this, PostAuthRouter.nextScreen(this)))
        finish()
    }
}
