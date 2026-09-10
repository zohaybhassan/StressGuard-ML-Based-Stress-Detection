package com.example.stressguard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.WorkoutSessionRepository
import com.example.stressguard.data.WorkoutSessionSummary
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WorkoutActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            lifecycleScope.launch { render() }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout)

        findViewById<MaterialToolbar>(R.id.workoutToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        bindStart(R.id.btnWorkoutStart30, 30 * 60_000L)
        bindStart(R.id.btnWorkoutStart60, 60 * 60_000L)
        bindStart(R.id.btnWorkoutStart90, 90 * 60_000L)
        bindStart(R.id.btnWorkoutStart120, 2 * 60 * 60_000L)

        findViewById<MaterialButton>(R.id.btnWorkoutPauseResume).setOnClickListener {
            lifecycleScope.launch {
                val current = WorkoutSessionRepository.current(this@WorkoutActivity)
                if (current?.isPaused == true) {
                    WorkoutSessionRepository.resume(this@WorkoutActivity)
                    Toast.makeText(this@WorkoutActivity, R.string.workout_resumed, Toast.LENGTH_SHORT).show()
                } else {
                    WorkoutSessionRepository.pause(this@WorkoutActivity)
                    Toast.makeText(this@WorkoutActivity, R.string.workout_paused, Toast.LENGTH_SHORT).show()
                }
                render()
            }
        }
        findViewById<MaterialButton>(R.id.btnWorkoutEnd).setOnClickListener {
            lifecycleScope.launch {
                WorkoutSessionRepository.end(this@WorkoutActivity)
                Toast.makeText(this@WorkoutActivity, R.string.workout_mode_ended, Toast.LENGTH_SHORT).show()
                render()
            }
        }

        fitSystemBars(top = findViewById(R.id.workoutRoot))
    }

    override fun onStart() {
        super.onStart()
        ticker.run()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun bindStart(buttonId: Int, durationMs: Long) {
        findViewById<MaterialButton>(buttonId).setOnClickListener {
            lifecycleScope.launch {
                WorkoutSessionRepository.start(this@WorkoutActivity, durationMs)
                Toast.makeText(this@WorkoutActivity, R.string.workout_started, Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private suspend fun render() {
        val now = System.currentTimeMillis()
        val current = WorkoutSessionRepository.current(this, now)
        val history = WorkoutSessionRepository.history(this, now)
        val displaySession = current ?: history.firstOrNull()

        renderStatus(current, displaySession)
        renderStats(current, displaySession)
        renderHistory(history)
    }

    private fun renderStatus(current: WorkoutSessionSummary?, displaySession: WorkoutSessionSummary?) {
        val status = findViewById<TextView>(R.id.tvWorkoutStatus)
        val startActions = findViewById<View>(R.id.workoutStartActions)
        val activeActions = findViewById<View>(R.id.workoutActiveActions)
        val pauseResume = findViewById<MaterialButton>(R.id.btnWorkoutPauseResume)

        when {
            current?.isPaused == true -> {
                status.setText(R.string.workout_paused_status)
                startActions.visibility = View.GONE
                activeActions.visibility = View.VISIBLE
                pauseResume.setText(R.string.workout_resume)
            }
            current?.isActive == true -> {
                status.setText(R.string.workout_active_status)
                startActions.visibility = View.GONE
                activeActions.visibility = View.VISIBLE
                pauseResume.setText(R.string.workout_pause)
            }
            displaySession?.isCompleted == true -> {
                status.setText(R.string.workout_saved_status)
                startActions.visibility = View.VISIBLE
                activeActions.visibility = View.GONE
            }
            else -> {
                status.setText(R.string.workout_inactive_status)
                startActions.visibility = View.VISIBLE
                activeActions.visibility = View.GONE
            }
        }
    }

    private fun renderStats(current: WorkoutSessionSummary?, displaySession: WorkoutSessionSummary?) {
        val session = displaySession
        val timer = findViewById<TextView>(R.id.tvWorkoutTimer)
        val timerDetail = findViewById<TextView>(R.id.tvWorkoutTimerDetail)
        val elapsedMs = session?.elapsedActiveMs ?: 0L

        timer.text = formatClock(if (current == null) elapsedMs else current.remainingMs)
        timerDetail.text = if (current == null) {
            getString(R.string.workout_timer_elapsed, formatDuration(elapsedMs))
        } else {
            getString(R.string.workout_timer_remaining, formatDuration(current.remainingMs))
        }

        findViewById<TextView>(R.id.tvWorkoutAvgHr).text =
            session?.averageHeartRate?.let { "$it bpm" } ?: getString(R.string.workout_no_hr)
        findViewById<TextView>(R.id.tvWorkoutHrRange).text =
            if (session?.minHeartRate != null && session.maxHeartRate != null) {
                "${session.minHeartRate}-${session.maxHeartRate} bpm"
            } else {
                getString(R.string.workout_no_hr)
            }
        findViewById<TextView>(R.id.tvWorkoutSteps).text =
            session?.stepCount?.let { String.format(Locale.getDefault(), "%,d", it) }
                ?: getString(R.string.workout_no_steps)
    }

    private fun renderHistory(history: List<WorkoutSessionSummary>) {
        val container = findViewById<LinearLayout>(R.id.workoutHistoryList)
        container.removeAllViews()
        if (history.isEmpty()) {
            val empty = TextView(this).apply {
                setText(R.string.workout_history_empty)
                setTextAppearance(R.style.TextAppearance_StressGuard_Body)
            }
            container.addView(empty)
            return
        }

        history.forEach { session ->
            val row = layoutInflater.inflate(R.layout.item_workout_history, container, false)
            row.findViewById<TextView>(R.id.tvWorkoutHistoryPrimary).text =
                Instant.ofEpochMilli(session.startedAtEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(timeFormatter)
            val average = session.averageHeartRate?.let { "$it bpm" } ?: "--"
            row.findViewById<TextView>(R.id.tvWorkoutHistoryMeta).text = getString(
                R.string.workout_history_row,
                readableStatus(session),
                formatDuration(session.elapsedActiveMs),
                String.format(Locale.getDefault(), "%,d", session.stepCount),
                average,
            )
            container.addView(row)
        }
    }

    private fun readableStatus(session: WorkoutSessionSummary): String = when {
        session.isActive -> getString(R.string.workout_active_status)
        session.isPaused -> getString(R.string.workout_paused_status)
        else -> getString(R.string.workout_saved_status)
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        return if (minutes < 60L) {
            "$minutes min"
        } else {
            val hours = minutes / 60L
            val remainder = minutes % 60L
            if (remainder == 0L) "$hours hr" else "$hours hr $remainder min"
        }
    }
}
