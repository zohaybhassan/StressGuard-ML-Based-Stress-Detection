package com.example.stressguard

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.LocalUserData
import com.example.stressguard.data.Recommendation
import com.example.stressguard.data.RecommendationAction
import com.example.stressguard.data.RiskLevel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private lateinit var tvSyncStatus: TextView
    private lateinit var chipConnectionState: TextView
    private lateinit var stressGauge: CircularProgressIndicator
    private lateinit var btnSimulateModelInput: MaterialButton
    private lateinit var cvRecommendation: MaterialCardView
    private lateinit var tvRecommendationLevel: TextView
    private lateinit var tvRecommendationMessage: TextView
    private lateinit var tvRecommendationFactors: TextView

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

        // Re-read sleep every time the dashboard comes forward. Previously this happened once in
        // onCreate, so a night's sleep appearing in Health Connect -- or a provider being
        // installed that writes it at all -- had no effect until the app was killed and
        // relaunched. Deliberately does not re-request permission: refresh only, or a denied
        // permission would re-prompt on every resume.
        refreshSleepIfPermitted()

        // The checklist is edited on another screen, so coming back here is exactly when the
        // score may have changed. No network: this reads Room only.
        viewModel.refreshRecommendation()
    }

    /** Reads Health Connect again if the permission is already held. Never prompts. */
    private fun refreshSleepIfPermitted() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return

        lifecycleScope.launch {
            val granted = HealthConnectClient.getOrCreate(this@HomeDashboardActivity)
                .permissionController.getGrantedPermissions()
            if (granted.containsAll(sleepPermissions)) fetchSleepData()
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
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        // A manual sync, for when waiting for the periodic window would be wrong -- just after
        // signing in, or when demonstrating that queued rows do reach the backend.
        tvSyncStatus.setOnClickListener { viewModel.syncNow() }
        tvSleep.setOnClickListener { openHealthConnectSleepSettings() }
        chipConnectionState = findViewById(R.id.chipConnectionState)
        stressGauge = findViewById(R.id.stressGauge)
        btnSimulateModelInput = findViewById(R.id.btnSimulateModelInput)

        setUpToolbar()

        cvRecommendation = findViewById(R.id.cvRecommendation)
        tvRecommendationLevel = findViewById(R.id.tvRecommendationLevel)
        tvRecommendationMessage = findViewById(R.id.tvRecommendationMessage)
        tvRecommendationFactors = findViewById(R.id.tvRecommendationFactors)
        findViewById<MaterialButton>(R.id.btnEditChecklist).setOnClickListener {
            startActivity(HealthChecklistActivity.editIntent(this))
        }

        val userName = SessionManager.getUserName(this)?.takeIf { it.isNotBlank() } ?: "there"
        tvWelcome.text = "Welcome, $userName"

        btnSimulateModelInput.setOnClickListener { runNextDebugScenario() }

        askForNotificationPermission()
        checkHealthConnectPermissions()
        observeState()
    }

    /**
     * The toolbar was inflated but never wired to anything. It is where sign-out belongs: a
     * destructive, rarely used action does not want a button on the main surface next to the
     * things people tap every day.
     */
    private fun setUpToolbar() {
        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            inflateMenu(R.menu.dashboard_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit_checklist -> {
                        startActivity(HealthChecklistActivity.editIntent(this@HomeDashboardActivity))
                        true
                    }

                    R.id.action_sign_out -> {
                        confirmSignOut()
                        true
                    }

                    else -> false
                }
            }
        }
    }

    /**
     * Asks before signing out, and says what it costs.
     *
     * Signing out clears the local history, because none of it is keyed by user and the sync worker
     * stamps whatever is queued with whoever is signed in *at upload time* — so a previous user's
     * readings would otherwise be uploaded into the next user's account. Rows that never reached
     * Supabase are lost, so the count is shown rather than discovered afterwards.
     */
    private fun confirmSignOut() {
        lifecycleScope.launch {
            val pending = LocalUserData.pendingUploadCount(this@HomeDashboardActivity)

            val message = buildString {
                append("Your profile and stress history will be removed from this device.")
                if (pending > 0) {
                    append("\n\n")
                    append(pending)
                    append(if (pending == 1) " reading has" else " readings have")
                    append(" not reached the server yet and will be lost. Tap the sync line on ")
                    append("the dashboard first if you want to keep them.")
                }
            }

            AlertDialog.Builder(this@HomeDashboardActivity)
                .setTitle("Sign out?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out") { _, _ -> signOut() }
                .show()
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            AuthRepository.signOut()
            LocalUserData.clear(this@HomeDashboardActivity)

            startActivity(
                Intent(this@HomeDashboardActivity, LoginActivity::class.java)
                    // Nothing behind this should survive: the back stack holds screens rendered
                    // from the signed-out user's data.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
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
        // The reason travels with the value. A substituted figure that looks like a measurement
        // is worse than no figure, and this is the only place the user can find out that sleep
        // is not real.
        tvSleep.text = state.sleepHours?.let {
            val suffix = when {
                state.sleepAssumed && state.sleepDetail != null ->
                    " (assumed — ${state.sleepDetail}, tap to fix)"
                state.sleepAssumed -> " (assumed)"
                // A real reading can still carry a caveat, such as being two nights old.
                state.sleepDetail != null -> " (${state.sleepDetail})"
                else -> ""
            }
            "Sleep: ${String.format("%.1f", it)} hrs$suffix"
        } ?: "Sleep: Loading..."
        // Only actionable while the figure is a substitute. Health Connect's settings are the
        // only route back once the permission has been refused, and nothing else in the app
        // points there.
        tvSleep.isClickable = state.sleepAssumed

        renderSource(state)
        renderPrediction(state)
        renderRecommendation(state.recommendation)
        tvSyncStatus.text = state.sync.describe(System.currentTimeMillis())
    }

    /**
     * The rule-based checkup card, plan §7 and §16.
     *
     * Hidden entirely until there is enough history for a verdict. An always-visible card reading
     * "Low" on day one would be a claim the app has no grounds for, and the point of a rule-based
     * score is that every number on screen can be justified.
     */
    private fun renderRecommendation(recommendation: Recommendation?) {
        if (recommendation == null || recommendation.action == RecommendationAction.NOT_ENOUGH_DATA) {
            cvRecommendation.visibility = android.view.View.GONE
            return
        }

        cvRecommendation.visibility = android.view.View.VISIBLE
        tvRecommendationMessage.text = recommendation.message

        tvRecommendationLevel.text = "${recommendation.level.name} · ${recommendation.score}"
        tvRecommendationLevel.setTextColor(Color.parseColor(levelColor(recommendation.level)))

        // The card shows its own working. A score with no breakdown would throw away the reason
        // plan §7 chose rules over a model in the first place.
        tvRecommendationFactors.text = if (recommendation.hasFactors) {
            recommendation.factors.joinToString("\n") { "• ${it.description} (+${it.points})" }
        } else {
            "No risk factors recorded."
        }
    }

    private fun levelColor(level: RiskLevel): String = when (level) {
        RiskLevel.LOW -> "#69D18F"
        RiskLevel.MODERATE -> "#FFC107"
        RiskLevel.ELEVATED -> "#F9A825"
        RiskLevel.HIGH -> "#F44336"
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

    /**
     * Reads the wearer's most recent night from Health Connect.
     *
     * Two things this deliberately does not do.
     *
     * It does not look only at the last 24 hours. A provider syncs on its own schedule, and a
     * night that ended 26 hours ago is still the most recent one there is — the narrow window
     * reported "no sleep records" for data that was sitting right there. Seven days is wide
     * enough to find something and to tell "the provider writes nothing" apart from "nothing
     * recently".
     *
     * It does not sum everything it finds. Summing was survivable over 24 hours and becomes
     * nonsense over seven days: it would report a week of sleep as one night. Instead the most
     * recent session is taken, plus any earlier session close enough to be the same sleep
     * fragmented by brief waking, which is how sleep is usually recorded.
     */
    private fun fetchSleepData() {
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val records = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(
                            now.minus(SLEEP_LOOKBACK_DAYS, ChronoUnit.DAYS), now
                        ),
                    )
                ).records

                if (records.isEmpty()) {
                    // Health Connect answered; it simply holds nothing. Almost always a provider
                    // problem rather than ours: Samsung Health has to be installed, connected to
                    // Health Connect, and opened at least once for it to push anything.
                    Log.i(TAG, "Health Connect returned no sleep in the last $SLEEP_LOOKBACK_DAYS days")
                    useAssumedSleep("no sleep records")
                    return@launch
                }

                val night = mostRecentNight(records)
                val totalMillis = night.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
                val hours = (totalMillis / (1000.0 * 60.0 * 60.0)).toFloat()
                val endedHoursAgo = ChronoUnit.HOURS.between(night.first().endTime, now)

                Log.i(
                    TAG,
                    "read $hours h of sleep from ${night.size} session(s) ending ${endedHoursAgo}h " +
                        "ago; ${records.size} record(s) in the last $SLEEP_LOOKBACK_DAYS days"
                )

                viewModel.setSleepHours(
                    hours = hours,
                    assumed = false,
                    // A real measurement from two nights ago is worth more than the training-set
                    // mean, but the user should not think it is last night's.
                    detail = if (endedHoursAgo >= STALE_SLEEP_HOURS) "${endedHoursAgo / 24 + 1} days ago"
                    else null,
                )
            } catch (error: Exception) {
                Log.w(TAG, "Health Connect sleep read failed", error)
                useAssumedSleep("could not read Health Connect")
            }
        }
    }

    /**
     * The sessions making up the latest sleep, newest first.
     *
     * Sleep is often stored as several sessions separated by brief waking, so taking only the
     * newest would under-report a fragmented night. Walking backwards while the gap stays small
     * gathers one night without reaching into the one before it.
     */
    private fun mostRecentNight(records: List<SleepSessionRecord>): List<SleepSessionRecord> {
        val newestFirst = records.sortedByDescending { it.endTime }
        val night = mutableListOf(newestFirst.first())

        for (record in newestFirst.drop(1)) {
            val earliestStart = night.minOf { it.startTime }
            val gapHours = ChronoUnit.HOURS.between(record.endTime, earliestStart)
            if (gapHours > SLEEP_FRAGMENT_GAP_HOURS) break
            night += record
        }
        return night
    }

    /**
     * Sleep is one of the model's four continuous features, so a missing value would block
     * inference entirely. Substituting a default keeps the app predicting on a device where no
     * provider has ever written sleep data, and the UI labels the value as assumed.
     */
    private fun useAssumedSleep(reason: String) {
        // Only the ViewModel is told. Writing to the views directly is what hid this before:
        // tvConnectionState is rewritten by renderPrediction on the next state emission, and
        // tvSleep by render(), so both messages vanished within microseconds of appearing.
        Log.i(TAG, "sleep unavailable ($reason); using the training-set mean")
        viewModel.setSleepHours(
            hours = DashboardViewModel.DEFAULT_SLEEP_HOURS,
            assumed = true,
            detail = reason,
        )
    }

    /**
     * Opens Health Connect's permission screen for this app.
     *
     * Needed because a denied health permission is a dead end from inside the app. Android stops
     * showing the request dialog after it has been refused, so `requestSleepPermission.launch`
     * returns denied immediately and forever — the app can keep asking and the user will never
     * see anything. The only way back is Health Connect's own settings, and the user has no
     * reason to know that, so the "assumed" label is made tappable and brings them here.
     */
    private fun openHealthConnectSleepSettings() {
        val intents = listOf(
            // Per-app screen, straight to the permissions for this package.
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            // Older platforms and the standalone Health Connect APK use the androidx action.
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            // Last resort: the Health Connect home screen, from which the app is reachable.
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
        )

        for (intent in intents) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
        Log.w(TAG, "no Health Connect settings screen could be opened on this device")
    }

    private data class DebugScenario(
        val name: String,
        val heartRate: Int,
        val steps: Int,
        val sleepHours: Float,
    )

    companion object {
        private const val TAG = "VITALS"

        /**
         * How far back to look for sleep. Wide enough to distinguish a provider that writes
         * nothing from one that simply has not written today.
         */
        private const val SLEEP_LOOKBACK_DAYS = 7L

        /** Beyond this, the night found is labelled with its age rather than passed off as last night's. */
        private const val STALE_SLEEP_HOURS = 36L

        /** Sessions closer together than this are treated as one night broken by brief waking. */
        private const val SLEEP_FRAGMENT_GAP_HOURS = 4L

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
