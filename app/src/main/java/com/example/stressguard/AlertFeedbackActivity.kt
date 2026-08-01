package com.example.stressguard

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.sync.SyncScheduler
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class AlertFeedbackActivity : AppCompatActivity() {
    private val database by lazy { StressGuardDatabase.get(this) }
    private var feedbackId = 0L
    private var confirmedStressed: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert_feedback)
        feedbackId = intent.getLongExtra(EXTRA_FEEDBACK_ID, 0L)

        findViewById<MaterialToolbar>(R.id.feedbackToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val severityContainer = findViewById<View>(R.id.severityContainer)
        val severitySlider = findViewById<Slider>(R.id.severitySlider)
        val severityValue = findViewById<TextView>(R.id.tvSeverityValue)
        val save = findViewById<MaterialButton>(R.id.btnSaveFeedback)
        severitySlider.addOnChangeListener { _, value, _ ->
            severityValue.text = value.roundToInt().toString()
        }

        findViewById<MaterialButtonToggleGroup>(R.id.stressConfirmationGroup)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                confirmedStressed = checkedId == R.id.btnStressYes
                severityContainer.visibility = if (confirmedStressed == true) View.VISIBLE else View.GONE
                save.isEnabled = true
            }

        save.setOnClickListener {
            val confirmed = confirmedStressed ?: return@setOnClickListener
            save.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    database.stressFeedback().recordResponse(
                        id = feedbackId,
                        confirmedStressed = confirmed,
                        severity = if (confirmed) severitySlider.value.roundToInt() else null,
                        respondedAtEpochMs = System.currentTimeMillis(),
                    )
                    database.stressFeedback().byId(feedbackId)?.let {
                        database.alertEvents().markDismissed(it.alertEventId)
                    }
                }
                SyncScheduler.syncNow(this@AlertFeedbackActivity)
                getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
                Toast.makeText(this@AlertFeedbackActivity, R.string.feedback_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        fitSystemBars(top = findViewById(R.id.feedbackRoot))
        loadFeedback()
    }

    private fun loadFeedback() {
        if (feedbackId <= 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            val feedback = withContext(Dispatchers.IO) { database.stressFeedback().byId(feedbackId) }
            if (feedback == null || feedback.respondedAtEpochMs != null) {
                finish()
                return@launch
            }
            val time = Instant.ofEpochMilli(feedback.alertFiredAtEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a"))
            findViewById<TextView>(R.id.tvFeedbackContext).text = getString(
                R.string.feedback_context,
                time,
                (feedback.confidence * 100).roundToInt(),
            )
        }
    }

    companion object {
        private const val EXTRA_FEEDBACK_ID = "feedback_id"
        private const val ALERT_NOTIFICATION_ID = 1001

        fun intent(context: Context, feedbackId: Long): Intent =
            Intent(context, AlertFeedbackActivity::class.java)
                .putExtra(EXTRA_FEEDBACK_ID, feedbackId)
    }
}
