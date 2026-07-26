package com.example.stressguard.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.stressguard.presentation.theme.StressGuardTheme
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity(), SensorEventListener {

    private val pathVitals = "/stress_vitals"

    // UI State for Jetpack Compose
    private var displayState by mutableStateOf("Waiting for permissions...")

    // Variables to hold the live data
    private var currentHr: Int = 0
    private var currentSteps: Int = 0
    private val dummySleep = "7.2 hrs" // For UI polish during the demo

    // Sensor Manager for live step counting
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    /** TYPE_STEP_COUNTER counts from boot; the model wants steps today. */
    private lateinit var dailySteps: DailyStepCounter

    /** Registered callbacks have to be released, or heart rate measurement drains the battery. */
    private var measureCallback: MeasureCallback? = null

    /**
     * The step sensor fires on every change at SENSOR_DELAY_UI. Sending each one would put the
     * phone through a full inference, a database write and an alert evaluation several times a
     * second, for a step count that barely moved. Plan §4 is explicit that not every packet
     * should be forwarded.
     */
    private var lastSentAtElapsedMs = 0L

    // Permission Launcher for both sensors
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bodySensorsGranted = permissions[Manifest.permission.BODY_SENSORS] ?: false
        val activityGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false

        if (bodySensorsGranted && activityGranted) {
            startSensors()
        } else {
            // Name which one, and say it is recoverable. The generic message gave no way to
            // tell that heart rate specifically was blocked.
            val missing = buildList {
                if (!bodySensorsGranted) add("heart rate")
                if (!activityGranted) add("steps")
            }.joinToString(" and ")
            Log.w(TAG, "permission denied for $missing")
            displayState = "Permission needed for $missing.\nGrant it in Settings, then reopen."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Initialize the traditional Step Counter sensor for live updates
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        dailySteps = DailyStepCounter(this)

        if (stepSensor == null) {
            Log.w(TAG, "no step counter on this watch; steps will stay at 0")
        }

        if (hasSensorPermissions()) {
            startSensors()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION))
        }

        setContent { WearApp(displayState) }
    }

    /**
     * Recover if the permissions were granted outside the app.
     *
     * Previously sensors were only ever started from `onCreate`, so denying BODY_SENSORS once
     * left the app permanently showing "Permissions Denied!" — granting it later in settings
     * changed nothing until a reinstall. That is also easy to hit by accident, because the two
     * permissions are requested together and can be answered separately.
     */
    override fun onResume() {
        super.onResume()
        if (measureCallback == null && hasSensorPermissions()) {
            Log.i(TAG, "permissions now granted; starting sensors")
            startSensors()
        }
    }

    private fun hasSensorPermissions(): Boolean {
        val body = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
        val activity = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
        if (body != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BODY_SENSORS not granted; heart rate cannot be read and nothing will be sent")
        }
        if (activity != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted; step count will stay at 0")
        }
        return body == PackageManager.PERMISSION_GRANTED &&
            activity == PackageManager.PERMISSION_GRANTED
    }

    private fun startSensors() {
        displayState = "Starting sensors..."

        // 1. Start the Live Step Counter
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // 2. Start the Live HR Measure Client
        val healthClient = HealthServices.getClient(this)
        val measureClient = healthClient.measureClient

        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                // The usual reason nothing is sent: the watch is not on a wrist, so heart rate
                // never becomes available and the currentHr > 0 gate is never satisfied.
                Log.i(TAG, "heart rate availability: $availability")
                if (availability.toString().contains("UNAVAILABLE", ignoreCase = true)) {
                    displayState = "Wear the watch to read heart rate"
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val hrData = data.getData(DataType.HEART_RATE_BPM)
                if (hrData.isEmpty()) return

                val realHeartRate = hrData.last().value
                if (realHeartRate > 0.0) {
                    currentHr = realHeartRate.toInt()
                    updateUIAndSendData() // Route through master sync
                } else {
                    Log.d(TAG, "heart rate reported as 0; still acquiring")
                }
            }
        }
        measureCallback = callback
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        Log.i(TAG, "sensors started; waiting for a heart rate reading")
    }

    // --------------------------------------------------------
    // Step Sensor Callbacks
    // --------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            // The raw value counts from boot; convert it to steps taken today, which is what
            // the model's "Daily Steps" feature means.
            currentSteps = dailySteps.today(event.values[0].toLong())
            updateUIAndSendData() // Route through master sync
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for step counter
    }

    // --------------------------------------------------------
    // Master Updater: Syncs Compose UI and Phone BLE
    // --------------------------------------------------------
    private fun updateUIAndSendData() {
        if (currentHr <= 0) {
            displayState = "Calibrating HR..."
            return
        }

        // Always refresh the watch face; it costs nothing and shows the sensors are alive.
        displayState = "HR: $currentHr BPM\nSteps: $currentSteps\nSleep: $dummySleep"

        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAtElapsedMs < MIN_SEND_INTERVAL_MS) return
        lastSentAtElapsedMs = now

        sendRealDataToPhone("$currentHr|$currentSteps")
    }

    // --------------------------------------------------------
    // Secure BLE Transmission (AES)
    // --------------------------------------------------------
    private fun sendRealDataToPhone(sensorData: String) {
        val messageClient = Wearable.getMessageClient(this)
        val encryptedPayload = EncryptionUtil.encrypt(sensorData)

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    // The commonest failure and previously silent: the watch has no companion
                    // reachable, so the loop below simply did not execute.
                    Log.w(TAG, "no connected phone; $sensorData not sent")
                    displayState = "Phone not connected\nHR: $currentHr BPM"
                    return@addOnSuccessListener
                }

                for (node in nodes) {
                    messageClient.sendMessage(node.id, pathVitals, encryptedPayload)
                        .addOnSuccessListener {
                            Log.d(TAG, "sent $sensorData to ${node.displayName}")
                        }
                        .addOnFailureListener { error ->
                            // Typically the phone app not being installed, so nothing is
                            // listening on this path.
                            Log.w(TAG, "send to ${node.displayName} failed: ${error.message}")
                        }
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "could not list connected nodes: ${error.message}")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Both registrations have to be released. Leaving the heart rate callback registered
        // keeps the optical sensor measuring after the app closes.
        sensorManager.unregisterListener(this)

        val callback = measureCallback ?: return
        measureCallback = null
        // NonCancellable: onDestroy has already begun tearing the scope down, and a cancelled
        // unregister would leave the optical sensor running.
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            withContext(NonCancellable) {
                runCatching {
                    HealthServices.getClient(this@MainActivity).measureClient
                        .unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
                }
                    .onSuccess { Log.i(TAG, "heart rate callback unregistered") }
                    .onFailure { Log.w(TAG, "could not unregister the HR callback", it) }
            }
        }
    }

    companion object {
        private const val TAG = "WEAR_VITALS"

        /**
         * Minimum gap between messages. The step counter fires far more often than this, and
         * each send costs the phone an inference, a database write and an alert evaluation.
         */
        private const val MIN_SEND_INTERVAL_MS = 5_000L
    }
}

// --------------------------------------------------------
// Jetpack Compose UI Elements
// --------------------------------------------------------
@Composable
fun WearApp(sensorText: String) {
    StressGuardTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(sensorText = sensorText)
        }
    }
}

@Composable
fun Greeting(sensorText: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = sensorText // This dynamically updates whenever displayState changes
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("HR: 85 BPM\nSteps: 3450\nSleep: 7.2 hrs")
}