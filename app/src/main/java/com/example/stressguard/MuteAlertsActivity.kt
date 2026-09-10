package com.example.stressguard

import android.app.NotificationManager
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MuteAlertsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mute_alerts)

        findViewById<MaterialToolbar>(R.id.muteToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        bind(R.id.btnMute10Minutes, 10 * 60_000L)
        bind(R.id.btnMute30Minutes, 30 * 60_000L)
        bind(R.id.btnMute1Hour, 60 * 60_000L)
        bind(R.id.btnMute4Hours, 4 * 60 * 60_000L)
        findViewById<MaterialButton>(R.id.btnResumeAlerts).setOnClickListener {
            SessionManager.clearAlertMute(this)
            Toast.makeText(this, R.string.alerts_resumed, Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<MaterialButton>(R.id.btnNotificationSettings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        renderCurrentMute()
        fitSystemBars(top = findViewById(R.id.muteRoot))
    }

    private fun bind(buttonId: Int, durationMs: Long) {
        findViewById<MaterialButton>(buttonId).setOnClickListener {
            val until = System.currentTimeMillis() + durationMs
            SessionManager.muteAlertsUntil(this, until)
            getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
            val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a"))
            Toast.makeText(this, getString(R.string.alerts_muted_until, time), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun renderCurrentMute() {
        val until = SessionManager.getAlertsMutedUntil(this)
        val status = findViewById<TextView>(R.id.tvMuteStatus)
        if (until <= System.currentTimeMillis()) {
            status.setText(R.string.alerts_active)
            findViewById<MaterialButton>(R.id.btnResumeAlerts).isEnabled = false
            return
        }
        val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
        status.text = getString(R.string.alerts_currently_muted_until, time)
    }

    companion object {
        private const val ALERT_NOTIFICATION_ID = 1001
    }
}
