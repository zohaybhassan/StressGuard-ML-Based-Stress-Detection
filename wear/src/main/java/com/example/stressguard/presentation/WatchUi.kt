package com.example.stressguard.presentation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.stressguard.presentation.theme.Brand
import com.example.stressguard.presentation.theme.Canvas as CanvasColor
import com.example.stressguard.presentation.theme.Heart
import com.example.stressguard.presentation.theme.OnBrand
import com.example.stressguard.presentation.theme.Outline
import com.example.stressguard.presentation.theme.Steps
import com.example.stressguard.presentation.theme.StressGuardTheme
import com.example.stressguard.presentation.theme.StressHigh
import com.example.stressguard.presentation.theme.StressLow
import com.example.stressguard.presentation.theme.StressModerate
import com.example.stressguard.presentation.theme.Surface
import com.example.stressguard.presentation.theme.SurfaceVariant
import com.example.stressguard.presentation.theme.TextPrimary
import com.example.stressguard.presentation.theme.TextSecondary
import com.example.stressguard.BuildConfig
import kotlinx.coroutines.delay
import kotlin.math.ceil

data class WatchSensorUiState(
    val heartRate: Int = 0,
    val steps: Int = 0,
    val heartRateAgeMs: Long? = null,
    val statusMessage: String? = null,
    val foregroundPermissionGranted: Boolean = false,
    val backgroundPermissionGranted: Boolean = false,
    val phoneConnected: Boolean = false,
    val pendingFeedback: Boolean = false,
)

private enum class Screen {
    DASHBOARD,
    MENU,
    ALERT,
    BREATHING,
    CHECK_IN,
    WORKOUT,
    ALERT_CONTROLS,
    STATUS,
}

@Composable
fun StressGuardWearApp(
    sensor: WatchSensorUiState,
    received: ReceivedWatchState,
    onCommand: (WatchProtocol.Command) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var screenName by remember { mutableStateOf(Screen.DASHBOARD.name) }
    val screen = runCatching { Screen.valueOf(screenName) }.getOrDefault(Screen.DASHBOARD)

    LaunchedEffect(received.state.alertActive, received.state.feedbackId) {
        if (received.state.alertActive && received.state.feedbackId > 0L) {
            screenName = Screen.ALERT.name
        }
    }
    BackHandler(enabled = screen != Screen.DASHBOARD) {
        screenName = Screen.DASHBOARD.name
    }

    StressGuardTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
        ) {
            when (screen) {
                Screen.DASHBOARD -> DashboardScreen(
                    sensor = sensor,
                    received = received,
                    onBreathe = { screenName = Screen.BREATHING.name },
                    onMore = { screenName = Screen.MENU.name },
                    onStatus = { screenName = Screen.STATUS.name },
                )
                Screen.MENU -> MenuScreen { screenName = it.name }
                Screen.ALERT -> AlertScreen(
                    state = received.state,
                    onBreathe = { screenName = Screen.BREATHING.name },
                    onOkay = {
                        onCommand(
                            WatchProtocol.Command.SaveFeedback(
                                feedbackId = received.state.feedbackId,
                                confirmedStressed = false,
                                severity = null,
                            )
                        )
                        screenName = Screen.DASHBOARD.name
                    },
                    onCheckIn = { screenName = Screen.CHECK_IN.name },
                )
                Screen.BREATHING -> BreathingScreen { screenName = Screen.DASHBOARD.name }
                Screen.CHECK_IN -> CheckInScreen(
                    feedbackId = received.state.feedbackId,
                    onCommand = onCommand,
                    onDone = { screenName = Screen.DASHBOARD.name },
                )
                Screen.WORKOUT -> WorkoutScreen(
                    state = received.state,
                    sensor = sensor,
                    onCommand = onCommand,
                    onDone = { screenName = Screen.DASHBOARD.name },
                )
                Screen.ALERT_CONTROLS -> AlertControlsScreen(
                    state = received.state,
                    onCommand = onCommand,
                    onDone = { screenName = Screen.DASHBOARD.name },
                )
                Screen.STATUS -> StatusScreen(
                    sensor = sensor,
                    received = received,
                    onRefresh = { onCommand(WatchProtocol.Command.RequestState) },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    sensor: WatchSensorUiState,
    received: ReceivedWatchState,
    onBreathe: () -> Unit,
    onMore: () -> Unit,
    onStatus: () -> Unit,
) {
    val state = received.state
    val now = rememberNow()
    Box(Modifier.fillMaxSize()) {
        TimeText(modifier = Modifier.align(Alignment.TopCenter))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 23.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StressGauge(
                score = state.score,
                label = displayLabel(state),
                severity = severityColor(state.severity),
                outOfRange = state.outOfTrainingRange,
                size = 68.dp,
                onClick = onStatus,
            )
            Row(
                modifier = Modifier.width(132.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetricPill(
                    modifier = Modifier.weight(1f),
                    title = "HR",
                    value = if (sensor.heartRate > 0) "${sensor.heartRate} bpm" else "--",
                    color = Heart,
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    title = "STEPS",
                    value = sensor.steps.toString(),
                    color = Steps,
                )
            }
            Text(
                text = dashboardStatus(sensor, state, now),
                color = if (sensor.phoneConnected) TextSecondary else StressModerate,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PillAction(
                    text = "Breathe",
                    onClick = onBreathe,
                    modifier = Modifier.weight(1f),
                    primary = true,
                    compact = true,
                )
                PillAction(
                    text = "More",
                    onClick = onMore,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun StressGauge(
    score: Int?,
    label: String,
    severity: Color,
    outOfRange: Boolean,
    size: Dp,
    onClick: (() -> Unit)? = null,
) {
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Box(
        modifier = Modifier
            .size(size)
            .then(interactionModifier)
            .semantics {
                contentDescription = if (score == null) {
                    "Stress result unavailable, $label"
                } else {
                    "Current stress $score percent, $label"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 6.dp.toPx()
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(
                color = SurfaceVariant,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            if (score != null) {
                drawArc(
                    color = severity,
                    startAngle = 140f,
                    sweepAngle = 260f * score.coerceIn(0, 100) / 100f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score?.let { "$it%" } ?: "--",
                color = TextPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label + if (outOfRange) "*" else "",
                color = severity,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MetricPill(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(24.dp)
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = color, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(3.dp))
            Text(value, color = TextPrimary, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MenuScreen(onSelect: (Screen) -> Unit) = SecondaryPage(title = "StressGuard") {
    PageButton("Guided breathing") { onSelect(Screen.BREATHING) }
    PageButton("Stress check-in") { onSelect(Screen.CHECK_IN) }
    PageButton("Workout mode") { onSelect(Screen.WORKOUT) }
    PageButton("Alert controls") { onSelect(Screen.ALERT_CONTROLS) }
    PageButton("Monitoring status") { onSelect(Screen.STATUS) }
}

@Composable
private fun AlertScreen(
    state: WatchProtocol.State,
    onBreathe: () -> Unit,
    onOkay: () -> Unit,
    onCheckIn: () -> Unit,
) = SecondaryPage(title = "Elevated stress signal detected", titleColor = StressHigh) {
    StressGauge(
        score = state.score,
        label = displayLabel(state),
        severity = severityColor(state.severity),
        outOfRange = state.outOfTrainingRange,
        size = 88.dp,
    )
    Text(
        "Based on your recent readings. This is a wellness estimate, not a diagnosis.",
        color = TextSecondary,
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 9.dp),
    )
    PageButton("Start 1-minute breathing", primary = true, onClick = onBreathe)
    PageButton("I'm okay", onClick = onOkay)
    PageButton("Check in", onClick = onCheckIn)
}

@Composable
private fun BreathingScreen(onDone: () -> Unit) {
    val totalMs = 60_000L
    val cycleMs = 8_000L
    val start = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var totalPausedMs by remember { mutableLongStateOf(0L) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(paused) {
        while (!paused && elapsed < totalMs) {
            elapsed = (SystemClock.elapsedRealtime() - start - totalPausedMs).coerceAtMost(totalMs)
            delay(100L)
        }
    }
    val inCycle = elapsed % cycleMs
    val phase = when {
        elapsed >= totalMs -> "Complete"
        inCycle < 4_000L -> "Inhale"
        else -> "Exhale"
    }
    val phaseKey = when (phase) { "Inhale" -> 0; "Exhale" -> 1; else -> 2 }
    LaunchedEffect(phaseKey) {
        if (phaseKey < 2 && !paused) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val targetScale = when (phase) { "Inhale" -> 1f; "Exhale" -> .68f; else -> .82f }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 3_800),
        label = "breathing-ring",
    )
    val seconds = ceil((totalMs - elapsed).coerceAtLeast(0L) / 1000.0).toInt()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TimeText(modifier = Modifier.align(Alignment.TopCenter))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(94.dp)
                    .scale(scale)
                    .background(Brand.copy(alpha = .16f), CircleShape)
                    .border(5.dp, Brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phase, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (elapsed >= totalMs) "Well done" else "${seconds}s", color = TextSecondary)
                }
            }
            Spacer(Modifier.height(9.dp))
            if (elapsed >= totalMs) {
                PillAction("Done", onDone, Modifier.width(112.dp), primary = true, compact = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillAction(
                        text = if (paused) "Resume" else "Pause",
                        onClick = {
                            val now = SystemClock.elapsedRealtime()
                            if (paused) {
                                totalPausedMs += (now - pausedAt).coerceAtLeast(0L)
                                paused = false
                            } else {
                                pausedAt = now
                                paused = true
                            }
                        },
                        modifier = Modifier.width(76.dp),
                        primary = true,
                        compact = true,
                    )
                    PillAction("End", onDone, Modifier.width(76.dp), compact = true)
                }
            }
        }
    }
}

@Composable
private fun CheckInScreen(
    feedbackId: Long,
    onCommand: (WatchProtocol.Command) -> Unit,
    onDone: () -> Unit,
) {
    var answer by remember { mutableStateOf<Boolean?>(null) }
    var severity by remember { mutableIntStateOf(3) }
    var submitted by remember { mutableStateOf(false) }
    SecondaryPage(title = "How do you feel?") {
        if (submitted) {
            Text("Response saved", color = StressLow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("It will sync when the phone is reachable.", color = TextSecondary, textAlign = TextAlign.Center)
            PageButton("Done", primary = true, onClick = onDone)
        } else if (feedbackId <= 0L) {
            Text(
                "No stress check-in is waiting right now.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            PageButton("Done", primary = true, onClick = onDone)
        } else if (answer == null) {
            Text("Are you feeling stressed?", color = TextPrimary, textAlign = TextAlign.Center)
            PageButton("Yes") { answer = true }
            PageButton("No") {
                onCommand(WatchProtocol.Command.SaveFeedback(feedbackId, false, null))
                submitted = true
            }
        } else {
            Text("Stress intensity", color = TextSecondary)
            Text("$severity / 5", color = StressModerate, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(if (severity <= 2) "Mild" else if (severity >= 4) "Strong" else "Moderate", color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillAction("−", { severity = (severity - 1).coerceAtLeast(1) }, Modifier.size(48.dp))
                PillAction("+", { severity = (severity + 1).coerceAtMost(5) }, Modifier.size(48.dp))
            }
            PageButton("Save check-in", primary = true) {
                onCommand(WatchProtocol.Command.SaveFeedback(feedbackId, true, severity))
                submitted = true
            }
            PageButton("Skip severity") {
                onCommand(WatchProtocol.Command.SaveFeedback(feedbackId, true, null))
                submitted = true
            }
        }
    }
}

@Composable
private fun WorkoutScreen(
    state: WatchProtocol.State,
    sensor: WatchSensorUiState,
    onCommand: (WatchProtocol.Command) -> Unit,
    onDone: () -> Unit,
) {
    val now = rememberNow()
    val active = state.workoutStatus != WatchProtocol.WorkoutStatus.NONE
    SecondaryPage(title = "Workout mode") {
        if (!active) {
            Text(
                "Pauses stress prediction so exercise heart rate is not recorded as stress.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            listOf(30, 60, 90, 120).forEach { minutes ->
                PageButton("Start for $minutes minutes", primary = minutes == 30) {
                    onCommand(WatchProtocol.Command.StartWorkout(minutes * 60_000L))
                }
            }
        } else {
            val remaining = (state.workoutUntilEpochMs - now).coerceAtLeast(0L)
            val minutes = remaining / 60_000
            val seconds = (remaining / 1_000) % 60
            Text(
                if (state.workoutStatus == WatchProtocol.WorkoutStatus.PAUSED) "PAUSED" else "ACTIVE",
                color = Brand,
                fontWeight = FontWeight.SemiBold,
            )
            Text("%02d:%02d remaining".format(minutes, seconds), color = TextPrimary, fontSize = 22.sp)
            Text(
                "HR ${sensor.heartRate.takeIf { it > 0 } ?: "--"} bpm  •  Steps ${sensor.steps}",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            PageButton(
                if (state.workoutStatus == WatchProtocol.WorkoutStatus.PAUSED) "Resume" else "Pause",
                primary = true,
            ) {
                onCommand(
                    if (state.workoutStatus == WatchProtocol.WorkoutStatus.PAUSED) {
                        WatchProtocol.Command.ResumeWorkout
                    } else {
                        WatchProtocol.Command.PauseWorkout
                    }
                )
            }
            PageButton("End workout") {
                onCommand(WatchProtocol.Command.EndWorkout)
                onDone()
            }
        }
    }
}

@Composable
private fun AlertControlsScreen(
    state: WatchProtocol.State,
    onCommand: (WatchProtocol.Command) -> Unit,
    onDone: () -> Unit,
) {
    val now = rememberNow()
    val muted = state.alertsMutedUntilEpochMs > now
    SecondaryPage(title = "Alert controls") {
        if (muted) {
            val remaining = ceil((state.alertsMutedUntilEpochMs - now) / 60_000.0).toInt()
            Text("Alerts paused for $remaining more min", color = StressModerate, textAlign = TextAlign.Center)
            PageButton("Resume alerts", primary = true) {
                onCommand(WatchProtocol.Command.ResumeAlerts)
                onDone()
            }
        } else {
            Text("Alerts are active", color = StressLow)
            Text(
                "Monitoring and predictions continue while alerts are paused.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
            )
            listOf(10, 30, 60, 240).forEach { minutes ->
                PageButton("Pause for ${if (minutes < 60) "$minutes min" else "${minutes / 60} hr"}") {
                    onCommand(WatchProtocol.Command.MuteAlerts(minutes * 60_000L))
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    sensor: WatchSensorUiState,
    received: ReceivedWatchState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) = SecondaryPage(title = "Monitoring status") {
    StatusRow("Heart rate & steps", sensor.foregroundPermissionGranted)
    StatusRow("Background monitoring", sensor.backgroundPermissionGranted)
    StatusRow("Phone connection", sensor.phoneConnected)
    StatusRow("Stress result received", received.state.score != null)
    StatusRow("Pending feedback sync", !sensor.pendingFeedback)
    sensor.heartRateAgeMs?.let { Text("Heart rate age: ${it / 1_000}s", color = TextSecondary, fontSize = 11.sp) }
    if (received.receivedAtEpochMs > 0L) {
        Text(
            "Phone update age: ${(System.currentTimeMillis() - received.receivedAtEpochMs).coerceAtLeast(0L) / 1_000}s",
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
    received.state.takeIf { it.outOfTrainingRange }?.let {
        Text("* Current inputs are outside the model's trained range.", color = StressModerate, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
    PageButton("Refresh phone state", primary = true, onClick = onRefresh)
    PageButton("Open app settings", onClick = onOpenSettings)
    Text("StressGuard ${BuildConfig.VERSION_NAME}", color = TextSecondary, fontSize = 10.sp)
}

@Composable
private fun StatusRow(label: String, okay: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 11.sp)
        Text(if (okay) "OK" else "CHECK", color = if (okay) StressLow else StressModerate, fontSize = 10.sp)
    }
}

@Composable
private fun SecondaryPage(
    title: String,
    titleColor: Color = TextPrimary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        TimeText(modifier = Modifier.align(Alignment.TopCenter))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 29.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = titleColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            content()
        }
    }
}

@Composable
private fun PageButton(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) = PillAction(
    text = text,
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    primary = primary,
)

@Composable
private fun PillAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
                .height(48.dp)
            .background(if (primary) Brand else Surface, shape)
            .border(1.dp, if (primary) Brand else Outline, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) OnBrand else TextPrimary,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun displayLabel(state: WatchProtocol.State): String = when (state.status) {
    WatchProtocol.Status.WAITING -> "WAITING"
    WatchProtocol.Status.PROFILE_NEEDED -> "PHONE SETUP"
    WatchProtocol.Status.MODEL_ERROR -> "MODEL ERROR"
    WatchProtocol.Status.WORKOUT -> "WORKOUT"
    WatchProtocol.Status.READY -> state.label
}

private fun severityColor(severity: WatchProtocol.Severity): Color = when (severity) {
    WatchProtocol.Severity.LOW -> StressLow
    WatchProtocol.Severity.MODERATE -> StressModerate
    WatchProtocol.Severity.HIGH -> StressHigh
    WatchProtocol.Severity.UNKNOWN -> TextSecondary
}

private fun dashboardStatus(
    sensor: WatchSensorUiState,
    state: WatchProtocol.State,
    nowEpochMs: Long,
): String {
    sensor.statusMessage?.let { return it.replace('\n', ' ') }
    if (!sensor.phoneConnected) return "Phone disconnected • vitals stay local"
    if (state.workoutStatus != WatchProtocol.WorkoutStatus.NONE) return "Workout mode • stress prediction paused"
    if (state.measuredAtEpochMs <= 0L) return "Waiting for the first phone prediction"
    val seconds = (nowEpochMs - state.measuredAtEpochMs).coerceAtLeast(0L) / 1_000
    return when {
        seconds < 5 -> "Live • phone prediction"
        seconds < 60 -> "Updated ${seconds}s ago"
        else -> "Stress data ${seconds / 60}m old"
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun DashboardPreview() {
    StressGuardWearApp(
        sensor = WatchSensorUiState(
            heartRate = 79,
            steps = 857,
            heartRateAgeMs = 2_000,
            foregroundPermissionGranted = true,
            backgroundPermissionGranted = true,
            phoneConnected = true,
        ),
        received = ReceivedWatchState(
            state = WatchProtocol.State(
                status = WatchProtocol.Status.READY,
                score = 36,
                label = "NORMAL",
                severity = WatchProtocol.Severity.LOW,
                measuredAtEpochMs = System.currentTimeMillis(),
            ),
            receivedAtEpochMs = System.currentTimeMillis(),
        ),
        onCommand = {},
        onOpenSettings = {},
    )
}
