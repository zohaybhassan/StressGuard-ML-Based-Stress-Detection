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
        /**
         * The step figure the model was given, which is not `reading.dailySteps` whenever the day
         * is still young. See [StepHistory].
         */
        val activityLevel: Int,
        /** The values given to the model fell outside its training ranges. */
        val extrapolating: Boolean,
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
    private val stepHistory = StepHistory(database.dailyStepTotals())

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

        // The model's "Daily Steps" means a full-day activity level, not a count that restarts at
        // midnight; see StepHistory. Debug scenarios name their own figure and must not be
        // rewritten by yesterday's real walking, or the three scenarios stop being distinguishable.
        val activityLevel = if (simulated) {
            reading.dailySteps
        } else {
            stepHistory.record(reading.dailySteps, reading.measuredAtEpochMs)
            stepHistory.activityLevel(reading.dailySteps, reading.measuredAtEpochMs)
        }

        val vitals = StressVitals(reading.heartRate, activityLevel, sleepHours)

        // Computed here rather than taken from the reading, because what matters is whether the
        // values the model actually received were inside its training ranges. The reading's own
        // flag is about the raw sample and now answers a different question.
        val extrapolating = isExtrapolating(reading.heartRate, activityLevel, sleepHours)

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

            store(prediction, reading, activityLevel, sleepHours, extrapolating)

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
                activityLevel = activityLevel,
                extrapolating = extrapolating,
            ).also { _latest.value = it }
        } catch (error: Exception) {
            Log.e(TAG, "prediction failed", error)
            PipelineResult.Failed("MODEL ERROR").also { _latest.value = it }
        }
    }

    private suspend fun store(
        prediction: StressPrediction,
        reading: SensorReading,
        activityLevel: Int,
        sleepHours: Float,
        extrapolating: Boolean,
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
                    // Both: what the watch measured, and what the model was told. They differ
                    // whenever the day is young, and a history that recorded only one of them
                    // could not explain its own predictions afterwards.
                    dailySteps = reading.dailySteps,
                    activityLevel = activityLevel,
                    sleepHours = sleepHours,
                    outOfTrainingRange = extrapolating,
                )
            )
        }.onFailure {
            // A failed write must not lose the prediction the user is looking at.
            Log.w(TAG, "could not store the prediction", it)
        }
    }

    /** Records a sleep figure just read from Health Connect, for later background predictions. */
    fun cacheSleepHours(hours: Float) = sleepCache.put(hours)

    /**
     * Forgets everything about the current user, on sign-out.
     *
     * The pipeline is process-scoped, so clearing the database is not enough: the smoothing window
     * and the last result live in memory and would otherwise carry into the next session. A window
     * holding the previous user's high-stress readings could fire an alert at the next user on
     * their first or second reading — the opposite of what "3 of the last 5" is for.
     *
     * The loaded ONNX graphs are deliberately kept. They are not user data, and dropping 13.8 MB of
     * models would make the next prediction a cold start for no reason.
     */
    suspend fun forgetUser() = mutex.withLock {
        recentClassIndices.clear()
        _latest.value = null
    }

    /**
     * Whether the values handed to the model fall outside what it was trained on.
     *
     * Worth recording rather than hiding: the trees clamp to their outermost leaf beyond these
     * ranges, so the prediction there is an extrapolation and a report quoting holdout accuracy
     * should say how often that happened.
     */
    private fun isExtrapolating(heartRate: Int, dailySteps: Int, sleepHours: Float): Boolean =
        heartRate !in SensorReading.TRAINED_HEART_RATE ||
            dailySteps !in SensorReading.TRAINED_STEPS ||
            sleepHours !in SensorReading.TRAINED_SLEEP_HOURS

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
