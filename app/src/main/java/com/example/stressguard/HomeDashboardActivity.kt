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
        val sleepHours = latestSleepHours ?: run {
            tvStressStatus.text = "WAITING FOR SLEEP"
            tvStressStatus.setTextColor(Color.parseColor("#AAB8B0"))
            return
        }

        val features = StressFeatureBuilder.build(
            context = this,
            heartRate = heartRate,
            dailySteps = steps,
            sleepHours = sleepHours,
        ) ?: run {
            tvStressStatus.text = "PROFILE NEEDED"
            tvStressStatus.setTextColor(Color.parseColor("#AAB8B0"))
            return
        }

        Log.d(
            "STRESS_MODEL",
            "Features=${features.joinToString(prefix = "[", postfix = "]")}"
        )

        runStressPrediction(features)
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

        val features = StressFeatureBuilder.build(
            context = this,
            heartRate = scenario.heartRate,
            dailySteps = scenario.steps,
            sleepHours = scenario.sleepHours,
        ) ?: run {
            tvStressStatus.text = "PROFILE NEEDED"
            tvStressStatus.setTextColor(Color.parseColor("#AAB8B0"))
            return
        }

        Log.d(
            "STRESS_MODEL",
            "ProfileSnapshot age=${SessionManager.getUserAge(this)}, " +
                "gender=${SessionManager.getUserGender(this)}, " +
                "occupation=${SessionManager.getUserOccupation(this)}, " +
                "bmi=${SessionManager.getUserBmi(this)}"
        )
        Log.d(
            "STRESS_MODEL",
            "DebugFeatures=${features.joinToString(prefix = "[", postfix = "]")}"
        )
        runStressPrediction(features)
    }

    private fun runStressPrediction(features: FloatArray) {
        lifecycleScope.launch {
            try {
                val prediction = withContext(Dispatchers.Default) {
                    val service = stressInferenceService ?: StressInferenceService(this@HomeDashboardActivity)
                        .also { stressInferenceService = it }
                    service.predict(features)
                }

                updateStressUi(prediction)
            } catch (error: Exception) {
                Log.e("STRESS_MODEL", "Stress prediction failed", error)
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

        val stressScore = (
            prediction.probabilities[0] * 15f +
                prediction.probabilities[1] * 55f +
                prediction.probabilities[2] * 90f
            ).roundToInt().coerceIn(0, 100)

        stressGauge.setProgressCompat(stressScore, true)
        tvStressPercentage.text = "$stressScore%"

        when (prediction.classIndex) {
            0 -> {
                tvStressStatus.text = "RELAXED"
                tvStressStatus.setTextColor(Color.parseColor("#69D18F"))
                stressGauge.setIndicatorColor(Color.parseColor("#69D18F"))
            }
            1 -> {
                tvStressStatus.text = "NORMAL"
                tvStressStatus.setTextColor(Color.parseColor("#FFC107"))
                stressGauge.setIndicatorColor(Color.parseColor("#FFC107"))
            }
            else -> {
                tvStressStatus.text = "HIGH STRESS"
                tvStressStatus.setTextColor(Color.parseColor("#F44336"))
                stressGauge.setIndicatorColor(Color.parseColor("#F44336"))
            }
        }
    }

    private data class DebugScenario(
        val name: String,
        val heartRate: Int,
        val steps: Int,
        val sleepHours: Float,
    )

    companion object {
        private val DEBUG_SCENARIOS = listOf(
            DebugScenario(
                name = "relaxed",
                heartRate = 62,
                steps = 11000,
                sleepHours = 8.3f,
            ),
            DebugScenario(
                name = "normal",
                heartRate = 84,
                steps = 6200,
                sleepHours = 6.5f,
            ),
            DebugScenario(
                name = "high stress",
                heartRate = 118,
                steps = 700,
                sleepHours = 4.1f,
            ),
        )
    }
}
