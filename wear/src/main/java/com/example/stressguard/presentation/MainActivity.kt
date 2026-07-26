package com.example.stressguard.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
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
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.unregisterMeasureCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /**
     * A condition that overrides the normal readout: a missing permission, a watch that cannot
     * measure heart rate, no reachable phone.
     *
     * Held here rather than written straight to [displayState] because the staleness ticker
     * repaints on a timer, and would otherwise wipe whichever message was on screen a few
     * seconds after it appeared. One writer for the display, several sources for what it says.
     */
    private var statusNote: String? = null

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

    /**
     * When the last heart rate sample actually arrived.
     *
     * Nothing polls the sensor: Health Services pushes samples on its own schedule, and on this
     * watch that schedule is irregular. Without recording the arrival time there is no way to
     * tell a heart rate measured a second ago from one measured a minute ago, and both were
     * being displayed -- and transmitted -- as though current.
     */
    private var lastHrAtElapsedMs = 0L

    /** Repaints the staleness age while the app is visible; see [startStalenessTicker]. */
    private var stalenessTicker: Job? = null

    // Permission Launcher for both sensors
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bodySensorsGranted = permissions[heartRatePermission] ?: false
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
            statusNote = "Permission needed for $missing.\nGrant it in Settings, then reopen."
            refreshDisplay()
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
            permissionLauncher.launch(
                arrayOf(heartRatePermission, Manifest.permission.ACTIVITY_RECOGNITION)
            )
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
        startStalenessTicker()
    }

    override fun onPause() {
        super.onPause()
        // Nothing to age while the face is not being looked at.
        stalenessTicker?.cancel()
        stalenessTicker = null
    }

    private fun hasSensorPermissions(): Boolean {
        val heartRate = ContextCompat.checkSelfPermission(this, heartRatePermission)
        val activity = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
        if (heartRate != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "$heartRatePermission not granted; heart rate cannot be read and nothing will be sent")
        }
        if (activity != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted; step count will stay at 0")
        }
        return heartRate == PackageManager.PERMISSION_GRANTED &&
            activity == PackageManager.PERMISSION_GRANTED
    }

    private fun startSensors() {
        statusNote = "Starting sensors..."
        refreshDisplay()

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
                statusNote =
                    if (availability.toString().contains("UNAVAILABLE", ignoreCase = true)) {
                        "Wear the watch to read heart rate"
                    } else {
                        // Cleared on ACQUIRING or AVAILABLE, so the readout comes back by
                        // itself once the sensor recovers.
                        null
                    }
                refreshDisplay()
            }

            override fun onDataReceived(data: DataPointContainer) {
                val hrData = data.getData(DataType.HEART_RATE_BPM)
                if (hrData.isEmpty()) return

                val realHeartRate = hrData.last().value
                if (realHeartRate > 0.0) {
                    val now = SystemClock.elapsedRealtime()
                    // The one place a heart rate enters the app, logged with the gap since the
                    // previous sample. The cadence is Health Services' choice, not ours, and it
                    // was previously unobservable: only sends were logged, and those were also
                    // triggered by the step sensor, so a frozen heart rate looked like a live one.
                    if (lastHrAtElapsedMs == 0L) {
                        Log.d(TAG, "first heart rate: $realHeartRate")
                    } else {
                        Log.d(TAG, "heart rate $realHeartRate, ${now - lastHrAtElapsedMs} ms after the last")
                    }
                    currentHr = realHeartRate.toInt()
                    lastHrAtElapsedMs = now
                    sendFreshReading()
                } else {
                    Log.d(TAG, "heart rate reported as 0; still acquiring")
                }
            }
        }
        measureCallback = callback
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        Log.i(TAG, "sensors started; waiting for a heart rate reading")

        // The interim message has served its purpose; fall through to the real readout rather
        // than waiting for the first availability callback, which may never come.
        statusNote = null
        refreshDisplay()

        runDiagnostics(measureClient)
    }

    /**
     * Answers two questions the app previously had no way to answer.
     *
     * **Can this watch measure heart rate on demand?** `registerMeasureCallback` succeeds
     * whether or not the data type is supported, so an unsupported watch looks identical to one
     * that is simply not being worn: no callback, no error, nothing in the log.
     *
     * **Is the phone reachable?** Connectivity was only ever checked inside the send path, and
     * that path requires a heart rate first. So with no heart rate the app could never report
     * whether the phone was connected, and the dashboard's "Watch Not Connected" only ever
     * meant "no reading has arrived" -- which conflates a missing watch with a missing sensor.
     */
    private fun runDiagnostics(measureClient: MeasureClient) {
        lifecycleScope.launch {
            runCatching { measureClient.getCapabilities() }
                .onSuccess { capabilities ->
                    val supported = capabilities.supportedDataTypesMeasure
                    val canMeasureHr = DataType.HEART_RATE_BPM in supported
                    Log.i(TAG, "measure capabilities: $supported")
                    if (canMeasureHr) {
                        Log.i(TAG, "this watch supports on-demand heart rate; wear it to get a reading")
                    } else {
                        Log.e(
                            TAG,
                            "this watch does NOT support on-demand heart rate via Health Services, " +
                                "so no reading will ever arrive through MeasureClient"
                        )
                        statusNote = "This watch cannot measure\nheart rate on demand"
                        refreshDisplay()
                    }
                }
                .onFailure { Log.w(TAG, "could not read measure capabilities", it) }

            reportPhoneConnectivity()
        }
    }

    /** Logs whether a companion phone is reachable, independent of having any reading to send. */
    private fun reportPhoneConnectivity() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w(TAG, "no companion phone reachable from this watch")
                } else {
                    Log.i(
                        TAG,
                        "phone reachable: " + nodes.joinToString { "${it.displayName} (nearby=${it.isNearby})" }
                    )
                }
            }
            .addOnFailureListener { Log.w(TAG, "could not query connected nodes", it) }
    }

    // --------------------------------------------------------
    // Step Sensor Callbacks
    // --------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            // The raw value counts from boot; convert it to steps taken today, which is what
            // the model's "Daily Steps" feature means.
            currentSteps = dailySteps.today(event.values[0].toLong())
            // Deliberately does not transmit. Steps ride along with the next heart rate sample;
            // see sendFreshReading for why a step event must not put a reading on the wire.
            refreshDisplay()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for step counter
    }

    // --------------------------------------------------------
    // Display and transmission, kept separate
    // --------------------------------------------------------

    /** How long ago the current heart rate was measured, or [Long.MAX_VALUE] if never. */
    private fun heartRateAgeMs(): Long =
        if (lastHrAtElapsedMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastHrAtElapsedMs

    /**
     * Repaints the watch face, showing the heart rate's age once it stops being current.
     *
     * A number with no age on it reads as a live measurement. It often is not: the sensor stops
     * producing samples the moment the watch loses skin contact, and Health Services only
     * measures while this app is in the foreground, so a screen timeout ends measurement too.
     */
    private fun refreshDisplay() {
        statusNote?.let { displayState = it; return }

        val ageMs = heartRateAgeMs()
        displayState = when {
            // Steps may be arriving while heart rate is not. Say which, rather than
            // "Calibrating", which gave no clue whether the sensor, the permission or the
            // wrist was the issue.
            currentHr <= 0 ->
                "Waiting for heart rate\nSteps: $currentSteps\nWear the watch snugly"

            ageMs > HR_STALE_AFTER_MS ->
                "HR: $currentHr (${ageMs / 1000}s old)\nSteps: $currentSteps\nKeep the watch on"

            else -> "HR: $currentHr BPM\nSteps: $currentSteps\nSleep: $dummySleep"
        }
    }

    /**
     * Transmits a reading, and is called only when a heart rate sample has just arrived.
     *
     * The step callback used to share this path. Because the payload is built from the last
     * known value of each field, a step event retransmitted whatever heart rate was current
     * when it was last measured -- so walking past the throttle re-sent a minute-old reading
     * repeatedly. The logs showed 89 BPM sent five times across 70 seconds while the step count
     * climbed 16 to 57.
     *
     * That is not cosmetic. The phone treats every message as an independent reading, so one
     * measurement became five predictions: five rows stored, five latency samples, and -- worst
     * -- five entries in the alert window. The rule is "3 of the last 5 predictions are high
     * stress", which exists to require a sustained trend; duplicates of a single measurement
     * satisfied it on their own.
     */
    private fun sendFreshReading() {
        refreshDisplay()

        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAtElapsedMs < MIN_SEND_INTERVAL_MS) return
        lastSentAtElapsedMs = now

        sendRealDataToPhone("$currentHr|$currentSteps")
    }

    /**
     * Ages the displayed heart rate on a timer while the app is visible.
     *
     * Both sensor callbacks are the only other things that repaint, so if the watch comes off
     * the wrist and the wearer stops moving, nothing fires again and the last heart rate stays
     * on screen indefinitely looking current. This is what makes the staleness in
     * [refreshDisplay] actually appear.
     */
    private fun startStalenessTicker() {
        stalenessTicker?.cancel()
        stalenessTicker = lifecycleScope.launch {
            while (true) {
                delay(STALENESS_TICK_MS)
                refreshDisplay()
            }
        }
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
                    statusNote = "Phone not connected\nHR: $currentHr BPM"
                    refreshDisplay()
                    return@addOnSuccessListener
                }

                for (node in nodes) {
                    messageClient.sendMessage(node.id, pathVitals, encryptedPayload)
                        .addOnSuccessListener {
                            Log.d(TAG, "sent $sensorData to ${node.displayName}")
                            // A send proving the link works clears any earlier complaint that
                            // it did not, so the watch does not keep claiming the phone is gone.
                            if (statusNote != null) {
                                statusNote = null
                                refreshDisplay()
                            }
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

    /**
     * Which permission actually governs heart rate on this watch.
     *
     * `BODY_SENSORS` was deprecated in Android 15 in favour of the granular health permission.
     * Because this module targets API 36, a Wear OS 5+ watch does not offer `BODY_SENSORS` at
     * all -- it never appears in the app's settings, so a user told to "grant it in settings"
     * finds nothing to grant. Requesting the right one per platform version fixes that.
     *
     * A string literal rather than a `Manifest.permission` constant: the constant is not
     * available at every compile SDK, and the value is stable.
     */
    private val heartRatePermission: String
        get() = if (Build.VERSION.SDK_INT >= 35) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }

    companion object {
        private const val TAG = "WEAR_VITALS"

        /**
         * Minimum gap between messages. Health Services can push heart rate samples faster than
         * this, and each send costs the phone an inference, a database write and an alert
         * evaluation. It is a floor, not a cadence: there is no upper bound, because nothing
         * polls the sensor and a watch off the wrist produces nothing to send.
         */
        private const val MIN_SEND_INTERVAL_MS = 5_000L

        /**
         * Past this, the displayed heart rate is labelled with its age rather than shown as a
         * bare number. Half a minute is already too old to describe someone's present stress.
         */
        private const val HR_STALE_AFTER_MS = 30_000L

        /** How often the age on screen is recomputed. */
        private const val STALENESS_TICK_MS = 5_000L
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