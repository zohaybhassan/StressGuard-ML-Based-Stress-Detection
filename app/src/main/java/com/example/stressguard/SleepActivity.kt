package com.example.stressguard

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.SleepDay
import com.example.stressguard.data.SleepPeriod
import com.example.stressguard.data.SleepRepository
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

class SleepActivity : AppCompatActivity() {
    private val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val oxygenPermission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    private val permissions = setOf(sleepPermission, oxygenPermission)
    private var permissionRequestStarted = false

    private lateinit var loading: View
    private lateinit var content: View
    private lateinit var unavailable: View
    private lateinit var tvUnavailable: TextView
    private var currentDay: SleepDay? = null

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { loadData() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep)

        loading = findViewById(R.id.sleepLoading)
        content = findViewById(R.id.sleepContent)
        unavailable = findViewById(R.id.sleepUnavailable)
        tvUnavailable = findViewById(R.id.tvSleepUnavailable)

        findViewById<MaterialToolbar>(R.id.sleepToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<MaterialButton>(R.id.btnManageSleepAccess).setOnClickListener {
            openHealthConnectSettings()
        }
        findViewById<MaterialButton>(R.id.btnManageOxygenAccess).setOnClickListener {
            openHealthConnectSettings()
        }
        setUpSleepTarget()
        fitSystemBars(top = findViewById(R.id.sleepRoot))
        checkPermissions(requestMissing = true)
    }

    override fun onResume() {
        super.onResume()
        if (permissionRequestStarted) checkPermissions(requestMissing = false)
    }

    private fun checkPermissions(requestMissing: Boolean) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            showUnavailable(getString(R.string.sleep_health_connect_unavailable))
            return
        }

        lifecycleScope.launch {
            val granted = HealthConnectClient.getOrCreate(this@SleepActivity)
                .permissionController.getGrantedPermissions()
            if (sleepPermission !in granted && requestMissing) {
                permissionRequestStarted = true
                requestPermissions.launch(permissions)
            } else if (sleepPermission !in granted) {
                showUnavailable(getString(R.string.sleep_permission_required))
            } else if (oxygenPermission !in granted && requestMissing) {
                permissionRequestStarted = true
                requestPermissions.launch(permissions)
            } else {
                loadData(granted)
            }
        }
    }

    private fun loadData(grantedPermissions: Set<String>? = null) {
        showLoading()
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@SleepActivity)
                val granted = grantedPermissions
                    ?: client.permissionController.getGrantedPermissions()
                if (sleepPermission !in granted) {
                    showUnavailable(getString(R.string.sleep_permission_required))
                    return@launch
                }

                val day = SleepRepository.readLatestDay(
                    client = client,
                    includeOxygen = oxygenPermission in granted,
                )
                if (day == null) {
                    showUnavailable(getString(R.string.sleep_no_records))
                } else {
                    render(day, canReadOxygen = oxygenPermission in granted)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Health Connect sleep details read failed", error)
                showUnavailable(getString(R.string.sleep_read_failed))
            }
        }
    }

    private fun render(day: SleepDay, canReadOxygen: Boolean) {
        currentDay = day
        loading.visibility = View.GONE
        unavailable.visibility = View.GONE
        content.visibility = View.VISIBLE

        val zone = ZoneId.systemDefault()
        findViewById<TextView>(R.id.tvSleepDate).text = day.date.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        )
        findViewById<TextView>(R.id.tvSleepTotal).text = formatDuration(day.totalDuration)
        findViewById<TextView>(R.id.tvSleepTotalCaption).text = if (day.naps.isEmpty()) {
            getString(R.string.sleep_main_only)
        } else {
            resources.getQuantityString(
                R.plurals.sleep_total_caption,
                day.naps.size,
                day.naps.size,
            )
        }
        findViewById<TextView>(R.id.tvMainDuration).text = formatDuration(day.mainSleep.duration)
        findViewById<TextView>(R.id.tvMainTime).text = formatRange(day.mainSleep, zone)
        renderSleepTarget()

        renderMetric(R.id.tvDeepSleep, day.stages.deep)
        renderMetric(R.id.tvLightSleep, day.stages.light)
        renderMetric(R.id.tvRemSleep, day.stages.rem)
        renderMetric(R.id.tvAwakeSleep, day.stages.awake)

        val napSection = findViewById<View>(R.id.napSection)
        val napList = findViewById<LinearLayout>(R.id.napList)
        napList.removeAllViews()
        napSection.visibility = if (day.naps.isEmpty()) View.GONE else View.VISIBLE
        day.naps.forEachIndexed { index, nap ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_sleep_period, napList, false)
            row.findViewById<TextView>(R.id.tvPeriodTitle).text = getString(
                R.string.sleep_nap_number,
                index + 1,
            )
            row.findViewById<TextView>(R.id.tvPeriodDuration).text = formatDuration(nap.duration)
            row.findViewById<TextView>(R.id.tvPeriodTime).text = formatRange(nap, zone)
            napList.addView(row)
        }

        val oxygenValue = findViewById<TextView>(R.id.tvOxygenValue)
        val oxygenDetail = findViewById<TextView>(R.id.tvOxygenDetail)
        val oxygenButton = findViewById<MaterialButton>(R.id.btnManageOxygenAccess)
        when {
            !canReadOxygen -> {
                oxygenValue.text = getString(R.string.metric_empty)
                oxygenDetail.text = getString(R.string.sleep_oxygen_permission)
                oxygenButton.visibility = View.VISIBLE
            }
            day.averageOxygenPercent != null -> {
                oxygenValue.text = getString(
                    R.string.sleep_oxygen_percent,
                    day.averageOxygenPercent,
                )
                oxygenDetail.text = resources.getQuantityString(
                    R.plurals.sleep_oxygen_samples,
                    day.oxygenSampleCount,
                    day.oxygenSampleCount,
                )
                oxygenButton.visibility = View.GONE
            }
            else -> {
                oxygenValue.text = getString(R.string.metric_empty)
                oxygenDetail.text = getString(R.string.sleep_oxygen_no_records)
                oxygenButton.visibility = View.GONE
            }
        }
    }

    private fun setUpSleepTarget() {
        val slider = findViewById<Slider>(R.id.sleepTargetSlider)
        slider.value = SessionManager.getSleepTargetMinutes(this) / 60f
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                findViewById<TextView>(R.id.tvSleepTarget).text =
                    formatDuration(Duration.ofMinutes((value * 60).toLong()))
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                SessionManager.setSleepTargetMinutes(
                    this@SleepActivity,
                    (slider.value * 60).toInt(),
                )
                renderSleepTarget()
            }
        })
        renderSleepTarget()
    }

    private fun renderSleepTarget() {
        val targetMinutes = SessionManager.getSleepTargetMinutes(this)
        val sleptMinutes = currentDay?.totalDuration?.toMinutes()?.toInt() ?: 0
        findViewById<TextView>(R.id.tvSleepTarget).text =
            formatDuration(Duration.ofMinutes(targetMinutes.toLong()))
        findViewById<LinearProgressIndicator>(R.id.sleepTargetProgress).setProgressCompat(
            ((sleptMinutes * 100f) / targetMinutes).toInt().coerceIn(0, 100),
            true,
        )
        findViewById<TextView>(R.id.tvSleepTargetProgress).text = getString(
            R.string.sleep_target_progress,
            formatDuration(Duration.ofMinutes(sleptMinutes.toLong())),
            formatDuration(Duration.ofMinutes(targetMinutes.toLong())),
        )
    }

    private fun renderMetric(viewId: Int, duration: Duration) {
        findViewById<TextView>(viewId).text = if (duration.isZero) {
            getString(R.string.metric_empty)
        } else {
            formatDuration(duration)
        }
    }

    private fun formatRange(period: SleepPeriod, zone: ZoneId): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return "${period.start.atZone(zone).format(formatter)} - " +
            period.end.atZone(zone).format(formatter)
    }

    private fun formatDuration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours == 0L -> getString(R.string.sleep_minutes, remainder)
            remainder == 0L -> getString(R.string.sleep_hours, hours)
            else -> getString(R.string.sleep_hours_minutes, hours, remainder)
        }
    }

    private fun showLoading() {
        loading.visibility = View.VISIBLE
        content.visibility = View.GONE
        unavailable.visibility = View.GONE
    }

    private fun showUnavailable(message: String) {
        loading.visibility = View.GONE
        content.visibility = View.GONE
        unavailable.visibility = View.VISIBLE
        tvUnavailable.text = message
    }

    private fun openHealthConnectSettings() {
        val intents = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
        )
        for (intent in intents) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
    }

    companion object {
        private const val TAG = "SLEEP"
    }
}
