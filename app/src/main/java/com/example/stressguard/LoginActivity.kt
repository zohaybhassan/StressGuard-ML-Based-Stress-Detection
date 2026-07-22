package com.example.stressguard // KEEP YOUR ACTUAL PACKAGE NAME

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task

class LoginActivity : AppCompatActivity() {

    private lateinit var tvLoginStatus: TextView
    private lateinit var btnGoogleSignIn: Button
    private lateinit var googleSignInClient: GoogleSignInClient

    // Modern way to handle Activity Results in Android
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleSignInResult(task)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvLoginStatus = findViewById(R.id.tvLoginStatus)

        // 1. Configure Google Sign-In
        // IMPORTANT: If you want to request an ID token for a backend later, you add .requestIdToken("YOUR_WEB_CLIENT_ID") here.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // 2. Check if user is already signed in (Skip login screen if they are)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            saveSignedInAccount(account)
            goToNextScreen()
            return
        }

        // 3. Setup the Button Click
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnGoogleSignIn.setOnClickListener {
            launchGoogleSignIn()
        }
    }

    private fun launchGoogleSignIn() {
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            // Signed in successfully! You can access account.email or account.displayName here if needed.
            saveSignedInAccount(account)
            tvLoginStatus.text = ""
            Toast.makeText(this, "Signed in as ${account.email ?: "user"}", Toast.LENGTH_SHORT).show()
            goToNextScreen()
        } catch (e: ApiException) {
            Log.e("AUTH", "Google sign-in failed with status code ${e.statusCode}", e)

            if (e.statusCode == 12501) {
                tvLoginStatus.text = "Sign-in was canceled."
                return
            }

            tvLoginStatus.text = when (e.statusCode) {
                10 -> "Google sign-in is not configured for this app yet."
                12500 -> "Google Play Services could not complete sign-in."
                else -> "Login failed with code ${e.statusCode}. Please try again."
            }
        }
    }

    private fun saveSignedInAccount(account: GoogleSignInAccount) {
        SessionManager.setSignedIn(this, true)
        SessionManager.saveGoogleAccount(
            context = this,
            displayName = account.displayName,
            email = account.email,
            photoUrl = account.photoUrl?.toString()
        )
    }

    private fun goToNextScreen() {
        val nextScreen = if (SessionManager.isProfileComplete(this)) {
            HomeDashboardActivity::class.java
        } else {
            ProfileSetupActivity::class.java
        }

        val intent = Intent(this, nextScreen)
        startActivity(intent)
        finish() // Destroys LoginActivity so the user can't hit "Back" to log out
    }
}
