package com.example.stressguard

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.stressguard.data.AlertDecision
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.LocalUserData
import com.example.stressguard.data.Recommendation
import com.example.stressguard.data.RecommendationAction
import com.example.stressguard.data.RiskLevel
import com.example.stressguard.data.SleepRepository
import com.example.stressguard.ui.StressRingView
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private lateinit var tvSleepDetail: TextView
    private lateinit var ivSleepChevron: ImageView
    private lateinit var cvSleep: MaterialCardView
    private lateinit var tvWelcome: TextView
    private lateinit var tvAvatar: TextView
    private lateinit var tvStressPercentage: TextView
    private lateinit var tvStressStatus: TextView
    private lateinit var tvConnectionState: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var chipConnectionState: Chip
    private lateinit var stressGauge: StressRingView
    private lateinit var liveDot: View
    private lateinit var cvRecommendation: MaterialCardView
    private lateinit var tvRecommendationLevel: TextView
    private lateinit var tvRecommendationMessage: TextView
    private lateinit var tvRecommendationFactors: TextView

    private val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)

    private val requestSleepPermission = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (sleepPermission in granted) {
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
        renderGreeting()
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
            if (sleepPermission in granted) fetchSleepData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_dashboard)

        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvSteps = findViewById(R.id.tvSteps)
        tvSleep = findViewById(R.id.tvSleep)
        tvSleepDetail = findViewById(R.id.tvSleepDetail)
        ivSleepChevron = findViewById(R.id.ivSleepChevron)
        tvWelcome = findViewById(R.id.tvWelcome)
        tvAvatar = findViewById(R.id.tvAvatar)
        tvStressPercentage = findViewById(R.id.tvStressPercentage)
        tvStressStatus = findViewById(R.id.tvStressStatus)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        chipConnectionState = findViewById(R.id.chipConnectionState)
        stressGauge = findViewById(R.id.stressGauge)
        liveDot = findViewById(R.id.liveDot)

        // A manual sync, for when waiting for the periodic window would be wrong -- just after
        // signing in, or when demonstrating that queued rows do reach the backend. The whole row
        // is the target rather than the label alone: a 12sp line of text is not a tap target.
        findViewById<View>(R.id.syncRow).setOnClickListener { viewModel.syncNow() }

        cvSleep = findViewById(R.id.cvSleep)
        cvSleep.setOnClickListener {
            startActivity(Intent(this, SleepActivity::class.java))
        }
        findViewById<View>(R.id.cvSteps).setOnClickListener {
            startActivity(
                Intent(this, StepsActivity::class.java)
                    .putExtra(StepsActivity.EXTRA_CURRENT_STEPS, viewModel.state.value.steps ?: 0)
            )
        }

        // "Feeling Overwhelmed?" had been on this screen since the first commit, wired to nothing.
        // The assistant is what it was always asking for: the card states a need, and now there
        // is somewhere to take it.
        findViewById<View>(R.id.btnEmergency).setOnClickListener {
            startActivity(AssistantActivity.fromAlert(this, viewModel.state.value.prediction?.label))
        }
        findViewById<View>(R.id.btnWorkoutMode).setOnClickListener {
            startActivity(Intent(this, MuteAlertsActivity::class.java))
        }

        setUpMenu()
        BottomNav.wire(this, findViewById(R.id.bottomNavigation), R.id.nav_home)
        fitSystemBars(
            top = findViewById(R.id.dashboardRoot),
            bottom = findViewById(R.id.bottomNavigation),
        )

        cvRecommendation = findViewById(R.id.cvRecommendation)
        tvRecommendationLevel = findViewById(R.id.tvRecommendationLevel)
        tvRecommendationMessage = findViewById(R.id.tvRecommendationMessage)
        tvRecommendationFactors = findViewById(R.id.tvRecommendationFactors)
        findViewById<MaterialButton>(R.id.btnEditChecklist).setOnClickListener {
            startActivity(HealthChecklistActivity.editIntent(this))
        }

        renderGreeting()

        askForNotificationPermission()
        checkHealthConnectPermissions()
        observeState()
    }

    /**
     * The user's name, and the initial standing in for an avatar there is nowhere to set.
     *
     * The greeting itself is a static label above this line, so the name gets the whole width and
     * can be ellipsised rather than pushing a "Welcome, " prefix off the screen with it.
     */
    private fun renderGreeting() {
        val userName = SessionManager.getUserName(this)?.takeIf { it.isNotBlank() }
        tvWelcome.text = userName ?: "there"
        tvAvatar.text = userName?.trim()?.firstOrNull()?.uppercase() ?: "?"
    }

    /**
     * The overflow menu, where sign-out belongs: a destructive, rarely used action does not want a
     * button on the main surface next to the things people tap every day.
     *
     * Anchored to the header's icon rather than to a toolbar. The dashboard scrolls its own header
     * away, and a top app bar pinned above that would have cost 64dp of every screenful to hold
     * two menu items and a title the user already knows.
     */
    private fun setUpMenu() {
        val anchor = findViewById<MaterialButton>(R.id.btnMenu)
        anchor.setOnClickListener {
            PopupMenu(this, anchor).apply {
                inflate(R.menu.dashboard_menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit_checklist -> {
                            startActivity(
                                HealthChecklistActivity.editIntent(this@HomeDashboardActivity)
                            )
                            true
                        }

                        R.id.action_settings -> {
                            startActivity(
                                Intent(this@HomeDashboardActivity, SettingsActivity::class.java)
                            )
                            true
                        }

                        R.id.action_sign_out -> {
                            confirmSignOut()
                            true
                        }

                        else -> false
                    }
                }
                show()
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

            // The Material builder rather than AppCompat's, so the dialog picks up the app's
            // shape and colours instead of the platform default it used to render in.
            MaterialAlertDialogBuilder(this@HomeDashboardActivity)
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
        tvHeartRate.text = state.heartRate?.let { "$it bpm" } ?: "--"
        tvSteps.text = state.steps?.let { "%,d".format(it) } ?: "--"

        renderSleep(state)
        renderSource(state)
        renderPrediction(state)
        renderRecommendation(state.recommendation)
        tvSyncStatus.text = state.sync.describe(System.currentTimeMillis())
    }

    /**
     * Sleep, with the reason it should or should not be trusted underneath it.
     *
     * The caveat used to be a parenthesis inside the value itself. It is a separate line now
     * because it is the difference between a measurement and a stand-in, and that does not belong
     * set in the same type as the figure it is qualifying — nor squeezed onto one line with it.
     */
    private fun renderSleep(state: DashboardUiState) {
        tvSleep.text = state.sleepHours?.let { "${String.format("%.1f", it)} hrs" } ?: "--"

        val detail = when {
            state.sleepHours == null -> "Reading Health Connect…"
            state.sleepAssumed && state.sleepDetail != null ->
                "Assumed — ${state.sleepDetail}. Tap to fix."
            state.sleepAssumed -> "Assumed from the training average. Tap to fix."
            // A real reading can still carry a caveat, such as being two nights old.
            state.sleepDetail != null -> "Measured ${state.sleepDetail}"
            else -> null
        }
        tvSleepDetail.text = detail.orEmpty()
        tvSleepDetail.visibility = if (detail == null) View.GONE else View.VISIBLE

        // The card always opens the breakdown, which also owns Health Connect recovery actions.
        cvSleep.isClickable = true
        ivSleepChevron.visibility = View.VISIBLE
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
            cvRecommendation.visibility = View.GONE
            return
        }

        cvRecommendation.visibility = View.VISIBLE
        tvRecommendationMessage.text = recommendation.message

        val level = color(levelColor(recommendation.level))
        tvRecommendationLevel.text = "${recommendation.level.name} · ${recommendation.score}"
        tvRecommendationLevel.setTextColor(level)
        // The badge behind it takes the same colour at a tenth of its strength, so the level reads
        // as a status rather than as one word that has been coloured in.
        tvRecommendationLevel.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(level, PILL_TINT_ALPHA))

        // The card shows its own working. A score with no breakdown would throw away the reason
        // plan §7 chose rules over a model in the first place.
        tvRecommendationFactors.text = if (recommendation.hasFactors) {
            recommendation.factors.joinToString("\n") { "• ${it.description} (+${it.points})" }
        } else {
            "No risk factors recorded."
        }
    }

    @ColorRes
    private fun levelColor(level: RiskLevel): Int = when (level) {
        RiskLevel.LOW -> R.color.stress_low
        RiskLevel.MODERATE -> R.color.stress_moderate
        RiskLevel.ELEVATED -> R.color.stress_elevated
        RiskLevel.HIGH -> R.color.stress_high
    }

    private fun renderSource(state: DashboardUiState) {
        when (state.source) {
            // "Watch Connected" beside a number that has not changed in minutes is the same
            // conflation as before, one step further along: the link is up but the sensor has
            // stopped producing, which is what happens the moment the watch leaves the wrist.
            ReadingSource.WATCH -> {
                tvConnectionState.text = state.sourceDetail
                if (state.isReadingStale) {
                    setLinkChip("Watch idle", R.color.stress_moderate)
                } else {
                    setLinkChip("Watch connected", R.color.success)
                }
            }
            ReadingSource.SIMULATED -> {
                tvConnectionState.text = state.sourceDetail
                setLinkChip("Simulated data", R.color.metric_steps)
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
                    setLinkChip("Watch connected", R.color.stress_moderate)
                }
                WatchLink.NO_WATCH -> {
                    tvConnectionState.text = "No watch reachable from this phone"
                    setLinkChip("Watch not connected", R.color.text_tertiary)
                }
                WatchLink.UNKNOWN -> {
                    tvConnectionState.text = "Checking for a watch…"
                    setLinkChip("Checking…", R.color.text_tertiary)
                }
            }
        }
    }

    /** The link chip's label and colour move together; the icon is tinted to match the text. */
    private fun setLinkChip(label: String, @ColorRes colorId: Int) {
        val tint = ColorStateList.valueOf(color(colorId))
        chipConnectionState.text = label
        chipConnectionState.setTextColor(tint)
        chipConnectionState.chipIconTint = tint
    }

    private fun renderPrediction(state: DashboardUiState) {
        state.workoutModeUntilEpochMs?.let { until ->
            val paused = color(R.color.metric_steps)
            stressGauge.setProgress(0)
            stressGauge.ringColor = paused
            liveDot.backgroundTintList = ColorStateList.valueOf(paused)
            tvStressPercentage.text = "--"
            tvStressStatus.text = "WORKOUT MODE"
            tvStressStatus.setTextColor(paused)
            tvConnectionState.text =
                "Stress predictions paused until ${formatClockTime(until)}. " +
                    "Heart rate and steps are still shown, but this workout will not affect Trends."
            return
        }

        if (state.error != null) {
            tvStressStatus.text = state.error
            tvStressStatus.setTextColor(color(R.color.text_on_dark_muted))
            return
        }

        val prediction = state.prediction ?: run {
            val neutral = color(R.color.text_on_dark_muted)
            stressGauge.setProgress(0)
            stressGauge.ringColor = neutral
            liveDot.backgroundTintList = ColorStateList.valueOf(neutral)
            tvStressPercentage.text = "--"
            tvStressStatus.text = "WAITING"
            tvStressStatus.setTextColor(neutral)
            return
        }
        val score = gaugeScore(prediction.probabilities)
        stressGauge.setProgress(score)
        tvStressPercentage.text = "$score%"

        // One colour drives the arc, the label and the live dot, so severity is legible from any
        // one of the three and they cannot disagree.
        val severity = color(severityColor(prediction.classIndex, prediction.probabilities.size))
        tvStressStatus.text = buildStatusText(state, prediction)
        tvStressStatus.setTextColor(severity)
        stressGauge.ringColor = severity
        liveDot.backgroundTintList = ColorStateList.valueOf(severity)

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
            is AlertDecision.UserMuted ->
                append("  |  alerts paused ").append(decision.remainingMs / 60_000).append("m")
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

    @ColorRes
    private fun severityColor(classIndex: Int, classCount: Int): Int = when {
        classIndex >= classCount - 1 -> R.color.stress_high
        classIndex == 0 -> R.color.stress_low
        else -> R.color.stress_moderate
    }

    private fun displayName(label: String): String = when (label.lowercase()) {
        "relaxed_low_stress" -> "RELAXED"
        "normal", "not_stressed" -> "NORMAL"
        "stressed_high", "stressed" -> "HIGH STRESS"
        else -> label.replace('_', ' ').uppercase()
    }

    /** Resolves a palette entry. Every colour on this screen comes through here, so the dark
     *  theme is a matter of which resource file answers rather than a second set of branches. */
    @ColorInt
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private fun formatClockTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

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
            if (sleepPermission in granted) {
                fetchSleepData()
            } else {
                requestSleepPermission.launch(setOf(sleepPermission))
            }
        }
    }

    /** Reads the latest local sleep day: main sleep plus every nap ending on that date. */
    private fun fetchSleepData() {
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val day = SleepRepository.readLatestDay(client, includeOxygen = false)
                if (day == null) {
                    // Health Connect answered; it simply holds nothing. Almost always a provider
                    // problem rather than ours: Samsung Health has to be installed, connected to
                    // Health Connect, and opened at least once for it to push anything.
                    Log.i(
                        TAG,
                        "Health Connect returned no sleep in the last " +
                            "${SleepRepository.LOOKBACK_DAYS} days"
                    )
                    useAssumedSleep("no sleep records")
                    return@launch
                }

                val hours = (day.totalDuration.toMinutes() / 60.0).toFloat()
                val endedHoursAgo = ChronoUnit.HOURS.between(
                    day.mainSleep.end,
                    java.time.Instant.now(),
                )

                Log.i(
                    TAG,
                    "read $hours h for ${day.date}: ${day.mainSleep.duration.toMinutes()}m main " +
                        "+ ${day.naps.sumOf { it.duration.toMinutes() }}m naps"
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

    companion object {
        private const val TAG = "VITALS"

        /** Beyond this, the night found is labelled with its age rather than passed off as last night's. */
        private const val STALE_SLEEP_HOURS = 36L

        // Gauge anchors, inset from 0 and 100 so the extremes still read as a filled arc.
        private const val GAUGE_MIN = 10f
        private const val GAUGE_MAX = 90f

        /** Roughly 12% opacity: enough for the risk badge to read as a shape, not as a block. */
        private const val PILL_TINT_ALPHA = 30
    }
}
