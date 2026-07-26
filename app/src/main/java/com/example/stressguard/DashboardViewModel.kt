package com.example.stressguard

import android.app.Application
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stressguard.data.AlertDecision
import com.example.stressguard.data.LatencySample
import com.example.stressguard.data.LatencySummary
import com.example.stressguard.data.LatencyTracker
import com.example.stressguard.data.SensorReading
import com.example.stressguard.data.SensorRepository
import com.example.stressguard.data.StressAlertManager
import com.example.stressguard.data.StressAlertPolicy
import com.example.stressguard.data.local.RETENTION_DAYS
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.StressPredictionEntity
import com.example.stressguard.data.local.purgeOlderThan
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the current reading came from, so the dashboard can label it honestly. */
enum class ReadingSource { WAITING, WATCH, SIMULATED }

/**
 * Whether a watch is paired and reachable, which is a different question from whether a reading
 * has arrived.
 *
 * Conflating the two is actively misleading: a watch that is paired but not being worn produces
 * no heart rate, and labelling that "Watch Not Connected" sends anyone debugging it to look at
 * the transport, which is working.
 */
enum class WatchLink { UNKNOWN, NO_WATCH, PAIRED_NO_DATA, STREAMING }

data class DashboardUiState(
    val heartRate: Int? = null,
    val steps: Int? = null,
    val sleepHours: Float? = null,
    /** True when no Health Connect record existed and a default was substituted. */
    val sleepAssumed: Boolean = false,
    val source: ReadingSource = ReadingSource.WAITING,
    val sourceDetail: String = "",
    val watchLink: WatchLink = WatchLink.UNKNOWN,
    /** Name of the paired watch, when one is reachable. */
    val watchName: String? = null,
    val prediction: StressPrediction? = null,
    /** The model extrapolated for this reading; see SensorReading.outOfTrainingRange. */
    val outOfTrainingRange: Boolean = false,
    /**
     * How long ago the displayed watch reading arrived, refreshed on a timer. Null for debug
     * samples, where an age would be meaningless.
     */
    val readingAgeMs: Long? = null,
    val latency: LatencySummary = LatencySummary.EMPTY,
    val lastAlertAtEpochMs: Long? = null,
    val lastDecision: AlertDecision? = null,
    val error: String? = null,
) {
    /** True when the displayed watch reading is too old to describe the wearer's present state. */
    val isReadingStale: Boolean
        get() = source == ReadingSource.WATCH &&
            (readingAgeMs ?: 0L) >= DashboardViewModel.STALE_READING_MS
}

/**
 * Owns the real-time path: reading in, prediction out, stored, timed, and possibly alerted.
 *
 * This exists because two pieces of state must outlive the Activity. The smoothing window
 * ([StressAlertPolicy.WINDOW] recent predictions) would reset on every rotation if it lived in
 * the Activity, so turning the screen would silently postpone an alert. The ONNX service would
 * likewise be closed and reloaded — 13.8 MB — on each configuration change.
 *
 * Nothing here needs the network. That is the point: plan §3 requires the whole
 * receive → inference → alert path to work offline, and Supabase syncs from the local store
 * afterwards.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StressGuardDatabase.get(application)
    private val latencyTracker = LatencyTracker(database.latencyMetrics())
    private val alertManager = StressAlertManager(application, database.alertEvents())

    /** Created once and kept, rather than per-Activity. */
    private var inference: StressInferenceService? = null

    /** Smoothing window. Survives rotation, which is the whole reason this class exists. */
    private val recentClassIndices = ArrayDeque<Int>()

    /** Arrival time of the displayed watch reading, for [tickReadingAge]. */
    private var lastWatchReadingAtElapsedMs: Long? = null

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            SensorRepository.latest.filterNotNull().collect { onWatchReading(it) }
        }
        viewModelScope.launch { refreshDerivedState() }
        viewModelScope.launch { purgeOldHistory() }
        viewModelScope.launch { tickReadingAge() }
        refreshWatchLink()
    }

    /**
     * Ages the displayed reading on a timer.
     *
     * State is otherwise only emitted when a reading arrives, so a watch that stops sending
     * leaves its last heart rate on screen indefinitely, reading as current. The watch stops
     * sending for ordinary reasons -- it comes off the wrist, or its screen times out, since
     * Health Services only measures heart rate while the watch app is in the foreground -- so
     * this is the normal case rather than an error case, and it needs to be visible.
     */
    private suspend fun tickReadingAge() {
        while (true) {
            delay(AGE_TICK_MS)
            val arrivedAt = lastWatchReadingAtElapsedMs ?: continue
            _state.value = _state.value.copy(
                readingAgeMs = SystemClock.elapsedRealtime() - arrivedAt
            )
        }
    }

    /**
     * Asks the Wearable API whether a watch is actually reachable.
     *
     * Without this the dashboard could only report whether a reading had arrived, so a paired
     * watch that simply was not being worn appeared identical to no watch at all.
     */
    fun refreshWatchLink() {
        Wearable.getNodeClient(getApplication<Application>()).connectedNodes
            .addOnSuccessListener { nodes ->
                val current = _state.value
                _state.value = current.copy(
                    watchName = nodes.firstOrNull()?.displayName,
                    watchLink = when {
                        nodes.isEmpty() -> WatchLink.NO_WATCH
                        current.source == ReadingSource.WATCH -> WatchLink.STREAMING
                        else -> WatchLink.PAIRED_NO_DATA
                    },
                )
                Log.i(
                    TAG,
                    if (nodes.isEmpty()) "no watch reachable"
                    else "watch reachable: " + nodes.joinToString { it.displayName }
                )
            }
            .addOnFailureListener {
                Log.w(TAG, "could not query connected watches", it)
                _state.value = _state.value.copy(watchLink = WatchLink.UNKNOWN)
            }
    }

    /** Called by the Activity once Health Connect has been consulted. */
    fun setSleepHours(hours: Float?, assumed: Boolean = false) {
        _state.value = _state.value.copy(sleepHours = hours, sleepAssumed = assumed)
    }

    private suspend fun onWatchReading(reading: SensorReading) {
        lastWatchReadingAtElapsedMs = reading.receivedAtElapsedMs
        _state.value = _state.value.copy(
            heartRate = reading.heartRate,
            steps = reading.dailySteps,
            source = ReadingSource.WATCH,
            sourceDetail = "Watch data live",
            watchLink = WatchLink.STREAMING,
            outOfTrainingRange = reading.outOfTrainingRange,
            readingAgeMs = 0L,
        )
        predict(reading)
    }

    /**
     * Debug scenarios go down the same path as a real reading, so the latency figures and the
     * alert rule are exercised by them. That is what makes them useful without a watch.
     */
    fun runDebugScenario(name: String, heartRate: Int, steps: Int, sleepHours: Float) {
        val reading = SensorReading.from(
            heartRate = heartRate,
            dailySteps = steps,
            receivedAtElapsedMs = SystemClock.elapsedRealtime(),
            receivedAtEpochMs = System.currentTimeMillis(),
        ) ?: run {
            _state.value = _state.value.copy(error = "Debug scenario $name has implausible values")
            return
        }

        // A debug sample has no watch behind it, so there is no arrival to age.
        lastWatchReadingAtElapsedMs = null
        _state.value = _state.value.copy(
            heartRate = heartRate,
            steps = steps,
            sleepHours = sleepHours,
            sleepAssumed = false,
            source = ReadingSource.SIMULATED,
            sourceDetail = "Debug sample: $name",
            outOfTrainingRange = reading.outOfTrainingRange,
            readingAgeMs = null,
        )
        viewModelScope.launch { predict(reading, sleepOverride = sleepHours) }
    }

    private suspend fun predict(reading: SensorReading, sleepOverride: Float? = null) {
        val profile = SessionManager.readProfile(getApplication()) ?: run {
            _state.value = _state.value.copy(error = "PROFILE NEEDED")
            return
        }

        val sleepHours = sleepOverride ?: _state.value.sleepHours ?: DEFAULT_SLEEP_HOURS
        val vitals = StressVitals(reading.heartRate, reading.dailySteps, sleepHours)

        try {
            val service = inference ?: withContext(Dispatchers.Default) {
                StressInferenceService(getApplication())
            }.also { inference = it }

            val sample = LatencySample(
                receivedAtElapsedMs = reading.receivedAtElapsedMs,
                receivedAtEpochMs = reading.receivedAtEpochMs,
                coldStart = !service.isWarm,
            )

            val prediction = withContext(Dispatchers.Default) {
                val features = StressFeatureBuilder.buildVector(
                    profile, vitals, service.modelInfo.featureNames
                )
                sample.markPreprocessed()
                service.predict(features).also { sample.markInferred() }
            }

            _state.value = _state.value.copy(prediction = prediction, error = null)
            sample.markUiUpdated()

            store(prediction, reading, sleepHours)

            recentClassIndices.addLast(prediction.classIndex)
            while (recentClassIndices.size > StressAlertPolicy.WINDOW) recentClassIndices.removeFirst()

            val decision = alertManager.onPrediction(
                recentClassIndices = recentClassIndices.toList(),
                highStressClassIndex = service.modelInfo.classCount - 1,
                modelVersion = prediction.modelVersion,
            )
            if (decision is AlertDecision.Fire) sample.markAlertFired()

            latencyTracker.record(sample)
            _state.value = _state.value.copy(lastDecision = decision)
            refreshDerivedState()
        } catch (error: Exception) {
            Log.e(StressInferenceService.TAG, "prediction failed", error)
            _state.value = _state.value.copy(error = "MODEL ERROR")
        }
    }

    private suspend fun store(
        prediction: StressPrediction,
        reading: SensorReading,
        sleepHours: Float,
    ) {
        runCatching {
            database.stressPredictions().insert(
                StressPredictionEntity(
                    recordedAtEpochMs = reading.receivedAtEpochMs,
                    label = prediction.label,
                    classIndex = prediction.classIndex,
                    confidence = prediction.confidence,
                    probabilities = prediction.probabilities.toList(),
                    modelVersion = prediction.modelVersion,
                    heartRate = reading.heartRate,
                    dailySteps = reading.dailySteps,
                    sleepHours = sleepHours,
                    outOfTrainingRange = reading.outOfTrainingRange,
                )
            )
        }.onFailure {
            // A failed write must not lose the prediction the user is looking at.
            Log.w(StressInferenceService.TAG, "could not store the prediction", it)
        }
    }

    private suspend fun refreshDerivedState() {
        runCatching {
            val summary = latencyTracker.summary()
            val lastAlert = database.alertEvents().mostRecent()?.firedAtEpochMs
            _state.value = _state.value.copy(latency = summary, lastAlertAtEpochMs = lastAlert)
        }
    }

    private suspend fun purgeOldHistory() {
        runCatching {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000L
            database.purgeOlderThan(cutoff)
        }
    }

    override fun onCleared() {
        inference?.close()
        inference = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "VITALS"

        /** How often [tickReadingAge] recomputes the on-screen age. */
        private const val AGE_TICK_MS = 5_000L

        /**
         * Past this, the reading on screen is labelled with its age. Matches the watch's own
         * staleness threshold so the two devices do not disagree about what counts as current.
         */
        const val STALE_READING_MS = 30_000L

        /**
         * Used when Health Connect holds no sleep record, so a missing provider does not stop
         * the app predicting. Close to the training set's mean of 7.75 hours.
         */
        const val DEFAULT_SLEEP_HOURS = 7.5f
    }
}
