package com.example.stressguard

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.stressguard.data.AlertDecision
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The dashboard. Renders [DashboardViewModel]'s state and nothing else.
 *
 * Sensor state, inference, the alert window and latency history all live in the ViewModel, so
 * rotating the screen no longer resets the alert window or reloads the ONNX graphs. What stays
 * here is what genuinely belongs to an Activity: view lookup, the Health Connect permission
 * dance, and turning state into pixels.
 */
class HomeDashboardActivity : AppCompatActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var tvHeartRate: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvSleep: TextView
    private lateinit var tvWelcome: TextView
    private lateinit var tvStressPercentage: TextView
    private lateinit var tvStressStatus: TextView
    private lateinit var tvConnectionState: TextView
    private lateinit var chipConnectionState: TextView
    private lateinit var stressGauge: CircularProgressIndicator
    private lateinit var btnSimulateModelInput: MaterialButton

    private var debugScenarioIndex = 0

    private val sleepPermissions = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    private val requestSleepPermission = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(sleepPermissions)) {
            fetchSleepData()
        } else {
            useAssumedSleep("permission denied")
        }
    }

    /**
     * Requested but not required. Without it the alert degrades to haptic only, which is the
     * part that matters; the notification is the fallback for when the app is backgrounded.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way */ }

    override fun onResume() {
        super.onResume()
        // The watch can be paired or unpaired while the dashboard is open.
        viewModel.refreshWatchLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_dashboard)

        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvSteps = findViewById(R.id.tvSteps)
        tvSleep = findViewById(R.id.tvSleep)
        tvWelcome = findViewById(R.id.tvWelcome)
        tvStressPercentage = findViewById(R.id.tvStressPercentage)
        tvStressStatus = findViewById(R.id.tvStressStatus)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        chipConnectionState = findViewById(R.id.chipConnectionState)
        stressGauge = findViewById(R.id.stressGauge)
        btnSimulateModelInput = findViewById(R.id.btnSimulateModelInput)

        val userName = SessionManager.getUserName(this)?.takeIf { it.isNotBlank() } ?: "there"
        tvWelcome.text = "Welcome, $userName"

        btnSimulateModelInput.setOnClickListener { runNextDebugScenario() }

        askForNotificationPermission()
        checkHealthConnectPermissions()
        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: DashboardUiState) {
        tvHeartRate.text = state.heartRate?.let { "$it BPM" } ?: "--"
        tvSteps.text = state.steps?.toString() ?: "--"
        tvSleep.text = state.sleepHours?.let {
            val suffix = if (state.sleepAssumed) " (assumed)" else ""
            "Sleep: ${String.format("%.1f", it)} hrs$suffix"
        } ?: "Sleep: Loading..."

        renderSource(state)
        renderPrediction(state)
    }

    private fun renderSource(state: DashboardUiState) {
        when (state.source) {
            // "Watch Connected" beside a number that has not changed in minutes is the same
            // conflation as before, one step further along: the link is up but the sensor has
            // stopped producing, which is what happens the moment the watch leaves the wrist.
            ReadingSource.WATCH -> if (state.isReadingStale) {
                tvConnectionState.text = state.sourceDetail
                chipConnectionState.text = "Watch Idle"
                chipConnectionState.setTextColor(Color.parseColor("#F9A825"))
            } else {
                tvConnectionState.text = state.sourceDetail
                chipConnectionState.text = "Watch Connected"
                chipConnectionState.setTextColor(Color.parseColor("#2E7D32"))
            }
            ReadingSource.SIMULATED -> {
                tvConnectionState.text = state.sourceDetail
                chipConnectionState.text = "Simulated Data"
                chipConnectionState.setTextColor(Color.parseColor("#0B57D0"))
            }
            // Distinguish "no watch" from "watch present but not sending". They look the same
            // from the dashboard's point of view but have completely different causes: the
            // second is almost always the watch not being worn, since heart rate needs skin
            // contact, and calling that "Not Connected" points at the transport instead.
            ReadingSource.WAITING -> when (state.watchLink) {
                WatchLink.STREAMING, WatchLink.PAIRED_NO_DATA -> {
                    tvConnectionState.text =
                        "${state.watchName ?: "Watch"} connected — waiting for a heart rate. " +
                            "Wear the watch snugly."
                    chipConnectionState.text = "Watch Connected"
                    chipConnectionState.setTextColor(Color.parseColor("#F9A825"))
                }
                WatchLink.NO_WATCH -> {
                    tvConnectionState.text = "No watch reachable from this phone"
                    chipConnectionState.text = "Watch Not Connected"
                    chipConnectionState.setTextColor(Color.parseColor("#757575"))
                }
                WatchLink.UNKNOWN -> {
                    tvConnectionState.text = "Checking for a watch…"
                    chipConnectionState.text = "Checking…"
                    chipConnectionState.setTextColor(Color.parseColor("#757575"))
                }
            }
        }
    }

    private fun renderPrediction(state: DashboardUiState) {
        if (state.error != null) {
            tvStressStatus.text = state.error
            tvStressStatus.setTextColor(Color.parseColor("#AAB8B0"))
            return
        }

        val prediction = state.prediction ?: return
        val score = gaugeScore(prediction.probabilities)
        stressGauge.setProgressCompat(score, true)
        tvStressPercentage.text = "$score%"

        val color = Color.parseColor(severityColor(prediction.classIndex, prediction.probabilities.size))
        tvStressStatus.text = buildStatusText(state, prediction)
        tvStressStatus.setTextColor(color)
        stressGauge.setIndicatorColor(color)

        tvConnectionState.text = buildDetailLine(state)
    }

    /**
     * The status line carries the extrapolation warning, because a prediction made outside the
     * trained range should not look identical to one made inside it.
     */
    private fun buildStatusText(state: DashboardUiState, prediction: StressPrediction): String {
        val name = displayName(prediction.label)
        return if (state.outOfTrainingRange) "$name*" else name
    }

    /** Latency and last-alert are surfaced here rather than in a new card, per the plan. */
    private fun buildDetailLine(state: DashboardUiState): String = buildString {
        val ageMs = state.readingAgeMs
        if (state.isReadingStale && ageMs != null) {
            // Replaces "Watch data live", which stops being true the moment the watch stops
            // sending, and gives the age so the reading can be judged rather than assumed.
            append("Last reading ").append(ageMs / 1000).append("s ago")
        } else {
            append(state.sourceDetail.ifBlank { "Reading received" })
        }

        state.latency.latestReceiveToPredictionMs?.let { latest ->
            append("  •  ").append(latest).append(" ms")
            state.latency.averageReceiveToPredictionMs?.let { average ->
                append(" (avg ").append(average.roundToInt()).append(" ms")
                append(", n=").append(state.latency.steadyStateSamples).append(")")
            }
        }

        if (state.outOfTrainingRange) append("  •  * outside trained range")

        when (val decision = state.lastDecision) {
            is AlertDecision.Fire -> append("  •  ALERT")
            is AlertDecision.InCooldown ->
                append("  •  alert muted ").append(decision.remainingMs / 60_000).append("m")
            else -> state.lastAlertAtEpochMs?.let {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - it)
                append("  •  last alert ").append(minutes).append("m ago")
            }
        }
    }

    /**
     * Expected stress on a 0-100 scale: each class sits at an anchor spread evenly from low to
     * high, weighted by its probability. Derived from the class count, so a binary or a
     * three-level bundle both work unchanged.
     */
    private fun gaugeScore(probabilities: FloatArray): Int {
        if (probabilities.size < 2) return 0
        val step = (GAUGE_MAX - GAUGE_MIN) / (probabilities.size - 1)
        return probabilities.withIndex()
            .sumOf { (index, p) -> (p * (GAUGE_MIN + step * index)).toDouble() }
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun severityColor(classIndex: Int, classCount: Int): String = when {
        classIndex >= classCount - 1 -> "#F44336"
        classIndex == 0 -> "#69D18F"
        else -> "#FFC107"
    }

    private fun displayName(label: String): String = when (label.lowercase()) {
        "relaxed_low_stress" -> "RELAXED"
        "normal", "not_stressed" -> "NORMAL"
        "stressed_high", "stressed" -> "HIGH STRESS"
        else -> label.replace('_', ' ').uppercase()
    }

    private fun runNextDebugScenario() {
        val scenario = DEBUG_SCENARIOS[debugScenarioIndex]
        debugScenarioIndex = (debugScenarioIndex + 1) % DEBUG_SCENARIOS.size
        viewModel.runDebugScenario(
            name = scenario.name,
            heartRate = scenario.heartRate,
            steps = scenario.steps,
            sleepHours = scenario.sleepHours,
        )
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkHealthConnectPermissions() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            useAssumedSleep("Health Connect unavailable")
            return
        }

        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(sleepPermissions)) {
                fetchSleepData()
            } else {
                requestSleepPermission.launch(sleepPermissions)
            }
        }
    }

    private fun fetchSleepData() {
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(now.minus(24, ChronoUnit.HOURS), now),
                    )
                )

                if (response.records.isEmpty()) {
                    useAssumedSleep("no sleep records")
                    return@launch
                }

                val totalMillis = response.records.sumOf {
                    it.endTime.toEpochMilli() - it.startTime.toEpochMilli()
                }
                viewModel.setSleepHours(
                    hours = (totalMillis / (1000.0 * 60.0 * 60.0)).toFloat(),
                    assumed = false,
                )
            } catch (_: Exception) {
                useAssumedSleep("could not read Health Connect")
            }
        }
    }

    /**
     * Sleep is one of the model's four continuous features, so a missing value would block
     * inference entirely. Substituting a default keeps the app predicting on a device where no
     * provider has ever written sleep data, and the UI labels the value as assumed.
     */
    private fun useAssumedSleep(reason: String) {
        viewModel.setSleepHours(DashboardViewModel.DEFAULT_SLEEP_HOURS, assumed = true)
        tvSleep.text = "Sleep: ${String.format("%.1f", DashboardViewModel.DEFAULT_SLEEP_HOURS)} hrs (assumed)"
        tvConnectionState.text = "Sleep unavailable: $reason"
    }

    private data class DebugScenario(
        val name: String,
        val heartRate: Int,
        val steps: Int,
        val sleepHours: Float,
    )

    companion object {
        // Gauge anchors, inset from 0 and 100 so the extremes still read as a filled arc.
        private const val GAUGE_MIN = 10f
        private const val GAUGE_MAX = 90f

        private val DEBUG_SCENARIOS = listOf(
            DebugScenario(name = "relaxed", heartRate = 62, steps = 11000, sleepHours = 8.3f),
            // Sits deliberately between the other two. Earlier values (HR 84, 6200 steps, 6.5h)
            // predicted "stressed" at ~0.60, because 6.5 hours is well below the training set's
            // 7.75h mean -- so a scenario labelled "normal" displayed HIGH STRESS.
            DebugScenario(name = "normal", heartRate = 80, steps = 5500, sleepHours = 7.2f),
            // Kept inside the training ranges (heart rate 43-109, steps 1000-16036, sleep
            // 5.1-10.0). Beyond those the trees clamp to their outermost leaf, so an
            // out-of-range demo value produces the same output as the range edge.
            DebugScenario(name = "high stress", heartRate = 105, steps = 1200, sleepHours = 5.3f),
        )
    }
}
