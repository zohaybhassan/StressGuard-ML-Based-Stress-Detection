package com.example.stressguard.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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
    private var currentSteps: Long = 0
    private val dummySleep = "7.2 hrs" // For UI polish during the demo

    // Sensor Manager for live step counting
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    // Permission Launcher for both sensors
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bodySensorsGranted = permissions[Manifest.permission.BODY_SENSORS] ?: false
        val activityGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false

        if (bodySensorsGranted && activityGranted) {
            startSensors()
        } else {
            displayState = "Permissions Denied!"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Initialize the traditional Step Counter sensor for live updates
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val hasBodySensors = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val hasActivityReq = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        if (hasBodySensors && hasActivityReq) {
            startSensors()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION))
        }

        setContent { WearApp(displayState) }
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
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}

            override fun onDataReceived(data: DataPointContainer) {
                val hrData = data.getData(DataType.HEART_RATE_BPM)
                if (hrData.isNotEmpty()) {
                    val realHeartRate = hrData.last().value

                    if (realHeartRate > 0.0) {
                        currentHr = realHeartRate.toInt()
                        updateUIAndSendData() // Route through master sync
                    }
                }
            }
        }
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    }

    // --------------------------------------------------------
    // Step Sensor Callbacks
    // --------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            // The step counter returns total steps since the watch booted up
            currentSteps = event.values[0].toLong()
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
        // Only update once we have valid HR data
        if (currentHr > 0) {
            val payload = "$currentHr|$currentSteps"

            // 1. Update Compose State (triggers a UI refresh on the watch)
            displayState = "HR: $currentHr BPM\nSteps: $currentSteps\nSleep: $dummySleep"

            // 2. Transmit to Phone
            sendRealDataToPhone(payload)
        } else {
            displayState = "Calibrating HR..."
        }
    }

    // --------------------------------------------------------
    // Secure BLE Transmission (AES)
    // --------------------------------------------------------
    private fun sendRealDataToPhone(sensorData: String) {
        val messageClient = Wearable.getMessageClient(this)

        // Encrypt the plain text string into unreadable AES bytes
        val encryptedPayload = EncryptionUtil.encrypt(sensorData)

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            for (node in nodes) {
                // Send the encrypted payload over the air
                messageClient.sendMessage(node.id, pathVitals, encryptedPayload)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Prevent battery drain by unregistering the live step sensor when app closes
        sensorManager.unregisterListener(this)
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