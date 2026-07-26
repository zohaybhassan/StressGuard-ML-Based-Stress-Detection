package com.example.stressguard

import android.app.Application
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stressguard.data.AlertDecision
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.LatencySummary
import com.example.stressguard.data.PipelineResult
import com.example.stressguard.data.SensorReading
import com.example.stressguard.data.StressPipeline
import com.example.stressguard.data.SupabaseConfig
import com.example.stressguard.data.sync.SyncScheduler
import com.example.stressguard.data.sync.SyncState
import com.example.stressguard.data.sync.SyncStatus
import com.example.stressguard.data.local.RETENTION_DAYS
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.purgeOlderThan
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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
    /**
     * Why sleep is a substitute rather than a measurement — "no sleep records", "permission
     * denied", "Health Connect unavailable".
     *
     * Part of the state rather than written straight to a view: the reason used to be put in
     * `tvConnectionState`, which `renderPrediction` overwrites microseconds later, so nobody ever
     * saw it. Sleep silently defaulting to the training-set mean, with no way to find out why, is
     * exactly the kind of thing that should be visible.
     */
    val sleepDetail: String? = null,
    val source: ReadingSource = ReadingSource.WAITING,
    val sourceDetail: String = "",
    val watchLink: WatchLink = WatchLink.UNKNOWN,
    /** Name of the paired watch, when one is reachable. */
    val watchName: String? = null,
    val prediction: StressPrediction? = null,
    /** The model extrapolated for this reading; see SensorReading.outOfTrainingRange. */
    val outOfTrainingRange: Boolean = false,
    /**
     * How long ago the displayed watch reading was measured, refreshed on a timer. Null for debug
     * samples, where an age would be meaningless.
     *
     * Measured, not received: background readings arrive in batches and can already be minutes
     * old, so this counts from when the sensor took the sample.
     */
    val readingAgeMs: Long? = null,
    val latency: LatencySummary = LatencySummary.EMPTY,
    val lastAlertAtEpochMs: Long? = null,
    val lastDecision: AlertDecision? = null,
    /** How much local history is still waiting for Supabase, and when it last got there. */
    val sync: SyncStatus = SyncStatus.UNKNOWN,
    val error: String? = null,
) {
    /** True when the displayed watch reading is too old to describe the wearer's present state. */
    val isReadingStale: Boolean
        get() = source == ReadingSource.WATCH &&
            (readingAgeMs ?: 0L) >= SensorReading.STALE_SAMPLE_MS
}

/**
 * Presents what [StressPipeline] produced, and nothing more.
 *
 * The pipeline does the work — inference, storage, smoothing, alerting — because it has to run
 * with no Activity in existence, now that the watch delivers vitals in the background. This class
 * exists to turn its output into something the dashboard can draw, to age that output so a stalled
 * watch is visible, and to answer the separate question of whether a watch is reachable at all.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = StressPipeline.get(application)
    private val database = StressGuardDatabase.get(application)

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /** When the displayed reading was measured on the watch, for [tickReadingAge]. */
    private var measuredAtElapsedMs: Long? = null

    init {
        viewModelScope.launch {
            pipeline.latest.filterNotNull().collect { render(it) }
        }
        viewModelScope.launch { refreshDerivedState() }
        viewModelScope.launch { purgeOldHistory() }
        viewModelScope.launch { tickReadingAge() }
        refreshWatchLink()

        // Idempotent, so calling it on every dashboard launch is fine. Scheduled here rather than
        // from StressPipeline: plan §4 and §25 both require sync to stay off the real-time path,
        // and enqueuing work per prediction would put it right beside one.
        SyncScheduler.ensureScheduled(application)
    }

    /** Asks for a sync now, for the moments where waiting half an hour would be wrong. */
    fun syncNow() {
        SyncScheduler.syncNow(getApplication())
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

    /**
     * Called by the Activity once Health Connect has been consulted.
     *
     * The figure is cached as well as displayed, because background predictions have no Activity
     * to read Health Connect for them and would otherwise fall back to the training-set mean.
     */
    fun setSleepHours(hours: Float?, assumed: Boolean = false, detail: String? = null) {
        if (hours != null && !assumed) pipeline.cacheSleepHours(hours)
        _state.value = _state.value.copy(
            sleepHours = hours,
            sleepAssumed = assumed,
            sleepDetail = detail,
        )
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

        // A debug sample has no watch behind it, so there is no measurement to age.
        measuredAtElapsedMs = null
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
        viewModelScope.launch {
            pipeline.process(reading, sleepOverride = sleepHours, simulated = true)
        }
    }

    private suspend fun render(result: PipelineResult) {
        when (result) {
            is PipelineResult.Failed -> {
                _state.value = _state.value.copy(error = result.message)
                return
            }

            is PipelineResult.Predicted -> {
                val reading = result.reading
                val current = _state.value

                // In phone-elapsed terms, when the sensor took this sample. Derived by
                // subtracting the age the watch measured, so the age shown afterwards includes
                // the batching delay and the transit rather than restarting at arrival.
                val measuredAt = reading.receivedAtElapsedMs - reading.sampleAgeMs
                if (!result.simulated) measuredAtElapsedMs = measuredAt

                _state.value = current.copy(
                    heartRate = reading.heartRate,
                    steps = reading.dailySteps,
                    sleepHours = result.sleepHours,
                    sleepAssumed = result.sleepAssumed,
                    source = if (result.simulated) ReadingSource.SIMULATED else ReadingSource.WATCH,
                    sourceDetail =
                        if (result.simulated) current.sourceDetail else "Watch data live",
                    watchLink =
                        if (result.simulated) current.watchLink else WatchLink.STREAMING,
                    prediction = result.prediction,
                    // The pipeline's flag, not the reading's: it reflects the values the model was
                    // actually given, which for steps is a full-day activity level rather than the
                    // partial count the watch reported.
                    outOfTrainingRange = result.extrapolating,
                    readingAgeMs =
                        if (result.simulated) null
                        else SystemClock.elapsedRealtime() - measuredAt,
                    lastDecision = result.decision,
                    error = null,
                )
                refreshDerivedState()
            }
        }
    }

    /**
     * Ages the displayed reading on a timer.
     *
     * State is otherwise only emitted when a reading arrives, and with background collection the
     * gap between readings is minutes. A watch that stops sending — off the wrist, out of
     * Bluetooth range — would leave its last heart rate on screen indefinitely, reading as
     * current, so this is the normal case rather than an error case.
     */
    private suspend fun tickReadingAge() {
        while (true) {
            delay(AGE_TICK_MS)
            // Sync status is refreshed on the same tick: the worker drains the queue from another
            // component entirely, so there is no event here to hang a refresh off.
            val sync = readSyncStatus()
            val measuredAt = measuredAtElapsedMs
            _state.value = _state.value.copy(
                sync = sync,
                readingAgeMs = measuredAt?.let { SystemClock.elapsedRealtime() - it }
                    ?: _state.value.readingAgeMs,
            )
        }
    }

    private suspend fun refreshDerivedState() {
        _state.value = _state.value.copy(
            latency = pipeline.latencySummary(),
            lastAlertAtEpochMs = pipeline.lastAlertAtEpochMs(),
            sync = readSyncStatus(),
        )
    }

    /**
     * Counts what is still queued for Supabase.
     *
     * Read from the database rather than tracked in memory, because the worker runs in a different
     * component and may have drained the queue since this ViewModel last looked.
     */
    private suspend fun readSyncStatus(): SyncStatus = runCatching {
        SyncStatus(
            pending = database.stressPredictions().countUnsynced() +
                database.latencyMetrics().countUnsynced() +
                database.alertEvents().countUnsynced(),
            lastSuccessEpochMs = SyncState(getApplication()).lastSuccessEpochMs,
            signedIn = AuthRepository.currentUser != null,
            backendConfigured = SupabaseConfig.isBackendConfigured,
        )
    }.getOrDefault(SyncStatus.UNKNOWN)

    private suspend fun purgeOldHistory() {
        runCatching {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000L
            database.purgeOlderThan(cutoff)
        }
    }

    companion object {
        private const val TAG = "VITALS"

        /** How often [tickReadingAge] recomputes the on-screen age. */
        private const val AGE_TICK_MS = 5_000L

        /** Kept for the Activity's Health Connect fallback path. */
        const val DEFAULT_SLEEP_HOURS = StressPipeline.DEFAULT_SLEEP_HOURS
    }
}
