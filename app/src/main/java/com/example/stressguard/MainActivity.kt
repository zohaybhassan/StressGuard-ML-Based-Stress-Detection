package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val nextScreen = when {
            !SessionManager.isSignedIn(this) -> LoginActivity::class.java
            !SessionManager.isProfileComplete(this) -> ProfileSetupActivity::class.java
            else -> HomeDashboardActivity::class.java
        }

        startActivity(Intent(this, nextScreen))
        finish()
    }
}
