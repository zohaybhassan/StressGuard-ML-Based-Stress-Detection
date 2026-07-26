package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.SessionManager
import com.example.stressguard.StressFeatureBuilder
import com.example.stressguard.StressInferenceService
import com.example.stressguard.StressPrediction
import com.example.stressguard.StressVitals
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.StressPredictionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What came of one reading. */
sealed interface PipelineResult {

    data class Predicted(
        val reading: SensorReading,
        val prediction: StressPrediction,
        val sleepHours: Float,
        /** True when no cached Health Connect figure was available and a default was used. */
        val sleepAssumed: Boolean,
        val decision: AlertDecision,
        /**
         * True for a debug scenario rather than a real watch reading.
         *
         * Carried through explicitly because the dashboard must not label a simulated sample as
         * live watch data, and it cannot reliably tell them apart afterwards — results arrive on
         * a shared flow that a background reading can post to at any moment.
         */
        val simulated: Boolean,
    ) : PipelineResult

    /** A message fit for the dashboard: "PROFILE NEEDED", "MODEL ERROR". */
    data class Failed(val message: String) : PipelineResult
}

/**
 * Reading in, prediction out: stored, timed, and possibly alerted.
 *
 * This is process-scoped rather than owned by the dashboard because vitals now arrive with the
 * app closed. Health Services delivers batches to the watch's passive listener at its own
 * cadence, the watch forwards them, and `VitalReceiverService` is started to take delivery
 * whether or not any Activity exists. When this logic lived in `DashboardViewModel`, a reading
 * arriving with no dashboard open was received, published, and then dropped — the app only
 * monitored while being watched.
 *
 * Two pieces of state must survive the Activity for correctness, not just convenience:
 *
 *  - the **smoothing window**. The alert rule is "3 of the last 5 predictions are high stress",
 *    which is meaningless if the window empties whenever the user closes the app. Sustained
 *    stress across a morning has to be able to accumulate.
 *  - the **loaded model**. Roughly 13.8 MB of ONNX graphs; reloading per reading would dominate
 *    the latency figures.
 *
 * Nothing here needs the network, which is the point: plan §3 requires the whole
 * receive → inference → alert path to work offline.
 */
class StressPipeline private constructor(private val context: Context) {

    private val database = StressGuardDatabase.get(context)
    private val latencyTracker = LatencyTracker(database.latencyMetrics())
    private val alertManager = StressAlertManager(context, database.alertEvents())
    private val sleepCache = SleepCache(context)

    /** Loaded once and kept for the life of the process. */
    private var inference: StressInferenceService? = null

    /** Survives the Activity, which is what makes the alert rule mean anything. */
    private val recentClassIndices = ArrayDeque<Int>()

    /**
     * Readings can arrive from the watch on a binder thread while a debug sample is being run
     * from the UI. Without this the two would interleave on the smoothing window and the ONNX
     * session.
     */
    private val mutex = Mutex()

    private val _latest = MutableStateFlow<PipelineResult?>(null)

    /** The most recent outcome, for any dashboard that happens to be open. */
    val latest: StateFlow<PipelineResult?> = _latest.asStateFlow()

    /**
     * Runs one reading all the way through.
     *
     * Suspends until the alert decision is made, so callers that must keep a service alive for
     * the duration — see `VitalReceiverService` — can simply wait for it.
     */
    suspend fun process(
        reading: SensorReading,
        sleepOverride: Float? = null,
        simulated: Boolean = false,
    ): PipelineResult = mutex.withLock { processLocked(reading, sleepOverride, simulated) }

    private suspend fun processLocked(
        reading: SensorReading,
        sleepOverride: Float?,
        simulated: Boolean,
    ): PipelineResult {
        val profile = SessionManager.readProfile(context)
            ?: return PipelineResult.Failed("PROFILE NEEDED").also { _latest.value = it }

        val cached = sleepOverride ?: sleepCache.hours()
        val sleepHours = cached ?: DEFAULT_SLEEP_HOURS
        val vitals = StressVitals(reading.heartRate, reading.dailySteps, sleepHours)

        return try {
            val service = inference ?: withContext(Dispatchers.Default) {
                StressInferenceService(context)
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

            PipelineResult.Predicted(
                reading = reading,
                prediction = prediction,
                sleepHours = sleepHours,
                sleepAssumed = cached == null,
                decision = decision,
                simulated = simulated,
            ).also { _latest.value = it }
        } catch (error: Exception) {
            Log.e(TAG, "prediction failed", error)
            PipelineResult.Failed("MODEL ERROR").also { _latest.value = it }
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
                    // When the vitals were measured, not when the phone heard about them. With
                    // passive batching those differ by minutes, and a trend built from arrival
                    // times would bunch a whole batch at one instant.
                    recordedAtEpochMs = reading.measuredAtEpochMs,
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
            Log.w(TAG, "could not store the prediction", it)
        }
    }

    /** Records a sleep figure just read from Health Connect, for later background predictions. */
    fun cacheSleepHours(hours: Float) = sleepCache.put(hours)

    suspend fun latencySummary(): LatencySummary =
        runCatching { latencyTracker.summary() }.getOrDefault(LatencySummary.EMPTY)

    suspend fun lastAlertAtEpochMs(): Long? =
        runCatching { database.alertEvents().mostRecent()?.firedAtEpochMs }.getOrNull()

    companion object {
        private const val TAG = "VITALS"

        /**
         * Used when no Health Connect figure is cached, so a missing provider does not stop the
         * app predicting. Close to the training set's mean of 7.75 hours.
         */
        const val DEFAULT_SLEEP_HOURS = 7.5f

        @Volatile
        private var instance: StressPipeline? = null

        /**
         * One instance per process. The receiver service and the dashboard are in the same
         * process, and they must share the smoothing window and the loaded model rather than
         * each keeping their own.
         */
        fun get(context: Context): StressPipeline =
            instance ?: synchronized(this) {
                instance ?: StressPipeline(context.applicationContext).also { instance = it }
            }
    }
}
