package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import java.time.Duration
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.settingProfile).setOnClickListener {
            startActivity(ProfileSetupActivity.editIntent(this))
        }
        findViewById<View>(R.id.settingSleepGoal).setOnClickListener {
            startActivity(Intent(this, SleepActivity::class.java))
        }
        findViewById<View>(R.id.settingStepGoal).setOnClickListener {
            startActivity(Intent(this, StepsActivity::class.java))
        }
        findViewById<View>(R.id.settingChecklist).setOnClickListener {
            startActivity(HealthChecklistActivity.editIntent(this))
        }
        findViewById<View>(R.id.settingHealthConnect).setOnClickListener {
            openHealthConnectSettings()
        }
        findViewById<View>(R.id.settingNotifications).setOnClickListener {
            startActivity(Intent(this, MuteAlertsActivity::class.java))
        }
        findViewById<TextView>(R.id.tvSettingsVersion).text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
        )
        fitSystemBars(top = findViewById(R.id.settingsRoot))
    }

    override fun onResume() {
        super.onResume()
        renderAccountAndGoals()
    }

    private fun renderAccountAndGoals() {
        val name = SessionManager.getUserName(this).orEmpty().ifBlank {
            getString(R.string.settings_profile_fallback)
        }
        findViewById<TextView>(R.id.tvSettingsAvatar).text =
            name.trim().firstOrNull()?.uppercase() ?: "?"
        findViewById<TextView>(R.id.tvSettingsName).text = name
        findViewById<TextView>(R.id.tvSettingsProfileDetail).text = listOfNotNull(
            SessionManager.getUserAge(this)?.let { getString(R.string.settings_age, it) },
            SessionManager.getUserOccupation(this),
        ).joinToString(" · ")

        val sleepMinutes = SessionManager.getSleepTargetMinutes(this)
        findViewById<TextView>(R.id.tvSettingsSleepGoal).text = formatDuration(sleepMinutes)
        findViewById<TextView>(R.id.tvSettingsStepGoal).text = String.format(
            Locale.getDefault(),
            "%,d",
            SessionManager.getStepTarget(this),
        )
    }

    private fun formatDuration(minutes: Int): String {
        val duration = Duration.ofMinutes(minutes.toLong())
        val hours = duration.toHours()
        val remainder = duration.minusHours(hours).toMinutes()
        return if (remainder == 0L) getString(R.string.sleep_hours, hours)
        else getString(R.string.sleep_hours_minutes, hours, remainder)
    }

    private fun openHealthConnectSettings() {
        val intents = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
        )
        for (intent in intents) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
    }
}
