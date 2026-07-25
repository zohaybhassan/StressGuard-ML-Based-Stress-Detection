package com.example.stressguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class HomeDashboardActivity : AppCompatActivity() {

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

    private var isReceiverRegistered = false
    private var latestHeartRate: Int? = null
    private var latestSteps: Int? = null
    private var latestSleepHours: Float? = null
    private var debugScenarioIndex = 0
    private var stressInferenceService: StressInferenceService? = null

    private val sleepPermissions = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "STRESS_DATA_UPDATE") return

            val hrValue = intent.getStringExtra("hr_value") ?: "--"
            val stepsValue = intent.getStringExtra("steps_value") ?: "--"

            tvHeartRate.text = "$hrValue BPM"
            tvSteps.text = stepsValue
            updateConnectionState(true)

            latestHeartRate = hrValue.toIntOrNull()
            latestSteps = stepsValue.toIntOrNull()
            runStressPredictionIfReady()
        }
    }

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(sleepPermissions)) {
            fetchSleepData()
        } else {
            latestSleepHours = null
            tvSleep.text = "Sleep: Permission denied"
        }
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
        tvSleep.text = "Sleep: Loading..."
        updateConnectionState(false)
        btnSimulateModelInput.setOnClickListener {
            simulateModelInput()
        }
        checkHealthConnectPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!isReceiverRegistered) {
            val filter = IntentFilter("STRESS_DATA_UPDATE")
            ContextCompat.registerReceiver(
                this,
                dataReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isReceiverRegistered) {
            unregisterReceiver(dataReceiver)
            isReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        stressInferenceService?.close()
        stressInferenceService = null
        super.onDestroy()
    }

    private fun updateConnectionState(connected: Boolean) {
        if (connected) {
            tvConnectionState.text = "Watch data live"
            chipConnectionState.text = "Watch Connected"
            chipConnectionState.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvConnectionState.text = "Waiting for watch data"
            chipConnectionState.text = "Watch Not Connected"
            chipConnectionState.setTextColor(Color.parseColor("#757575"))
        }
    }

    private fun checkHealthConnectPermissions() {
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            val healthConnectClient = HealthConnectClient.getOrCreate(this)

            lifecycleScope.launch {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (granted.containsAll(sleepPermissions)) {
                    fetchSleepData()
                } else {
                    requestPermissions.launch(sleepPermissions)
                }
            }
        } else {
            latestSleepHours = null
            tvSleep.text = "Sleep: Health Connect unavailable"
        }
    }

    private fun fetchSleepData() {
        val healthConnectClient = HealthConnectClient.getOrCreate(this)

        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val yesterday = now.minus(24, ChronoUnit.HOURS)

                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(yesterday, now)
                )

                val response = healthConnectClient.readRecords(request)

                if (response.records.isNotEmpty()) {
                    var totalSleepMillis = 0L
                    for (session in response.records) {
                        totalSleepMillis += session.endTime.toEpochMilli() - session.startTime.toEpochMilli()
                    }

                    val sleepHours = totalSleepMillis / (1000.0 * 60.0 * 60.0)
                    latestSleepHours = sleepHours.toFloat()
                    tvSleep.text = "Sleep: ${String.format("%.1f", sleepHours)} hrs"
                    runStressPredictionIfReady()
                } else {
                    latestSleepHours = null
                    tvSleep.text = "Sleep: No data found"
                }
            } catch (_: Exception) {
                latestSleepHours = null
                tvSleep.text = "Sleep: Error loading data"
            }
        }
    }

    private fun runStressPredictionIfReady() {
        val heartRate = latestHeartRate ?: return
        val steps = latestSteps ?: return

        // Sleep comes from Health Connect, which has no records on a device where no provider
        // has ever written any. Falling back keeps inference running instead of stalling the
        // dashboard forever; the label makes clear the value is assumed, not measured.
        val sleepHours = latestSleepHours ?: run {
            tvSleep.text = "Sleep: ${String.format("%.1f", DEFAULT_SLEEP_HOURS)} hrs (assumed)"
            DEFAULT_SLEEP_HOURS
        }

        runStressPrediction(StressVitals(heartRate, steps, sleepHours))
    }

    private fun simulateModelInput() {
        val scenario = DEBUG_SCENARIOS[debugScenarioIndex]
        debugScenarioIndex = (debugScenarioIndex + 1) % DEBUG_SCENARIOS.size

        latestHeartRate = scenario.heartRate
        latestSteps = scenario.steps
        latestSleepHours = scenario.sleepHours

        tvHeartRate.text = "${scenario.heartRate} BPM"
        tvSteps.text = scenario.steps.toString()
        tvSleep.text = "Sleep: ${String.format("%.1f", scenario.sleepHours)} hrs"
        tvConnectionState.text = "Debug sample: ${scenario.name}"
        chipConnectionState.text = "Simulated Data"
        chipConnectionState.setTextColor(Color.parseColor("#0B57D0"))

        Log.d(
            "STRESS_MODEL",
            "ProfileBasedDebugScenario=${scenario.name}, heartRate=${scenario.heartRate}, " +
                "steps=${scenario.steps}, sleep=${scenario.sleepHours}"
        )

        Log.d(
            StressInferenceService.TAG,
            "ProfileSnapshot age=${SessionManager.getUserAge(this)}, " +
                "gender=${SessionManager.getUserGender(this)}, " +
                "occupation=${SessionManager.getUserOccupation(this)}, " +
                "bmi=${SessionManager.getUserBmi(this)}"
        )

        runStressPrediction(
            StressVitals(scenario.heartRate, scenario.steps, scenario.sleepHours)
        )
    }

    private fun runStressPrediction(vitals: StressVitals) {
        val profile = SessionManager.readProfile(this) ?: run {
            tvStressStatus.text = "PROFILE NEEDED"
            tvStressStatus.setTextColor(Color.parseColor("#AAB8B0"))
            return
        }

        lifecycleScope.launch {
            try {
                val prediction = withContext(Dispatchers.Default) {
                    // Loading the three ONNX graphs is ~23 MB of work, so it happens here on a
                    // background dispatcher rather than during construction.
                    val service = stressInferenceService
                        ?: StressInferenceService(this@HomeDashboardActivity)
                            .also { stressInferenceService = it }
                    service.predict(profile, vitals)
                }

                updateStressUi(prediction)
            } catch (error: Exception) {
                Log.e(StressInferenceService.TAG, "Stress prediction failed", error)
                tvStressStatus.text = "MODEL ERROR"
                tvStressStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    private fun updateStressUi(prediction: StressPrediction) {
        Log.d(
            "STRESS_MODEL",
            "Prediction=${prediction.label}, confidence=${prediction.confidence}, " +
                "probabilities=${prediction.probabilities.joinToString(prefix = "[", postfix = "]")}"
        )

        val stressScore = gaugeScore(prediction.probabilities)
        stressGauge.setProgressCompat(stressScore, true)
        tvStressPercentage.text = "$stressScore%"

        val color = Color.parseColor(severityColor(prediction.classIndex, prediction.probabilities.size))
        tvStressStatus.text = displayName(prediction.label)
        tvStressStatus.setTextColor(color)
        stressGauge.setIndicatorColor(color)
    }

    /**
     * Expected stress on a 0-100 scale: each class sits at an anchor point spread evenly from
     * low to high, and the score is the probability-weighted average of those anchors. Derived
     * from the class count rather than hardcoded, so a binary bundle works unchanged.
     */
    private fun gaugeScore(probabilities: FloatArray): Int {
        if (probabilities.size < 2) return 0
        val step = (GAUGE_MAX - GAUGE_MIN) / (probabilities.size - 1)
        val score = probabilities.withIndex().sumOf { (index, p) ->
            (p * (GAUGE_MIN + step * index)).toDouble()
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun severityColor(classIndex: Int, classCount: Int): String = when {
        classIndex >= classCount - 1 -> "#F44336" // most severe class
        classIndex == 0 -> "#69D18F" // least severe class
        else -> "#FFC107"
    }

    /** Manifest class names are snake_case; show something readable. */
    private fun displayName(label: String): String = when (label.lowercase()) {
        "relaxed_low_stress" -> "RELAXED"
        "normal", "not_stressed" -> "NORMAL"
        "stressed_high", "stressed" -> "HIGH STRESS"
        else -> label.replace('_', ' ').uppercase()
    }

    private data class DebugScenario(
        val name: String,
        val heartRate: Int,
        val steps: Int,
        val sleepHours: Float,
    )

    companion object {
        /**
         * Used when Health Connect holds no sleep records, so a missing provider does not stop
         * the app from predicting. Close to the training set's mean of 7.75 hours.
         */
        private const val DEFAULT_SLEEP_HOURS = 7.5f

        // Gauge anchors, inset from 0 and 100 so the extremes still read as a filled arc.
        private const val GAUGE_MIN = 10f
        private const val GAUGE_MAX = 90f

        private val DEBUG_SCENARIOS = listOf(
            DebugScenario(
                name = "relaxed",
                heartRate = 62,
                steps = 11000,
                sleepHours = 8.3f,
            ),
            // Sits deliberately between the other two. The previous values (HR 84, 6200 steps,
            // 6.5h) predicted "stressed" at ~0.60, because 6.5 hours is well below the training
            // set's 7.75h mean -- so a scenario labelled "normal" displayed HIGH STRESS.
            DebugScenario(
                name = "normal",
                heartRate = 80,
                steps = 5500,
                sleepHours = 7.2f,
            ),
            // Kept inside the training set's ranges (heart rate 43-109, steps 1000-16036,
            // sleep 5.1-10.0). Beyond those the trees just clamp to their outermost leaf, so an
            // out-of-range demo value would produce the same output as the range edge and look
            // like the model was ignoring the input.
            DebugScenario(
                name = "high stress",
                heartRate = 105,
                steps = 1200,
                sleepHours = 5.3f,
            ),
        )
    }
}
