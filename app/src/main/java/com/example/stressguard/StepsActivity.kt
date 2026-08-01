package com.example.stressguard

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.StepHistory
import com.example.stressguard.data.local.DailyStepTotalEntity
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.ui.fitSystemBars
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class StepsActivity : AppCompatActivity() {
    private lateinit var chart: BarChart
    private var days: List<StepDay> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_steps)

        findViewById<MaterialToolbar>(R.id.stepsToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        chart = findViewById(R.id.chartSteps)
        setUpTarget()
        setUpChart()
        fitSystemBars(top = findViewById(R.id.stepsRoot))
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { loadSteps() }
    }

    private suspend fun loadSteps() {
        val stored = StressGuardDatabase.get(this).dailyStepTotals().recent(CHART_DAYS)
            .associateBy(DailyStepTotalEntity::date)
        val today = LocalDate.now()
        days = (CHART_DAYS - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            StepDay(date, stored[date.toString()]?.steps ?: 0)
        }
        renderTarget()
        renderChart()
    }

    private fun setUpTarget() {
        val slider = findViewById<Slider>(R.id.stepTargetSlider)
        slider.value = SessionManager.getStepTarget(this).toFloat()
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                findViewById<TextView>(R.id.tvStepTarget).text = formatSteps(value.toInt())
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                SessionManager.setStepTarget(this@StepsActivity, slider.value.toInt())
                renderTarget()
                renderChart()
            }
        })
        renderTarget()
    }

    private fun renderTarget() {
        val todaySteps = days.lastOrNull()?.steps
            ?: intent.getIntExtra(EXTRA_CURRENT_STEPS, 0).coerceAtLeast(0)
        val target = SessionManager.getStepTarget(this)
        findViewById<TextView>(R.id.tvTodaySteps).text = formatSteps(todaySteps)
        findViewById<TextView>(R.id.tvStepTarget).text = formatSteps(target)
        findViewById<LinearProgressIndicator>(R.id.stepTargetProgress).setProgressCompat(
            ((todaySteps * 100f) / target).toInt().coerceIn(0, 100),
            true,
        )
        findViewById<TextView>(R.id.tvStepProgressDetail).text = when {
            todaySteps >= target -> getString(R.string.steps_goal_reached, formatSteps(target))
            else -> getString(R.string.steps_remaining, formatSteps(target - todaySteps))
        }
    }

    private fun setUpChart() {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            axisMinimum = 0f
            textColor = color(R.color.chart_axis_text)
            gridColor = color(R.color.chart_grid)
            setDrawAxisLine(false)
            valueFormatter = CompactStepsFormatter
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
            setDrawAxisLine(false)
            textColor = color(R.color.chart_axis_text)
        }
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawBarShadow(false)
        chart.setNoDataText(getString(R.string.steps_no_history))
        chart.setNoDataTextColor(color(R.color.text_tertiary))
    }

    private fun renderChart() {
        if (days.isEmpty()) return
        val target = SessionManager.getStepTarget(this)
        val labels = days.map { it.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) }
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                labels.getOrNull(value.toInt()).orEmpty()
        }

        val set = BarDataSet(
            days.mapIndexed { index, day -> BarEntry(index.toFloat(), day.steps.toFloat()) },
            getString(R.string.steps_chart_title),
        ).apply {
            color = color(R.color.metric_steps)
            setDrawValues(false)
        }
        chart.data = BarData(set).apply { barWidth = 0.58f }
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(
            LimitLine(target.toFloat(), getString(R.string.steps_chart_goal, formatSteps(target))).apply {
                lineColor = color(R.color.brand)
                textColor = color(R.color.text_secondary)
                lineWidth = 1.5f
                enableDashedLine(10f, 7f, 0f)
                textSize = 10f
            }
        )
        val highest = maxOf(target, days.maxOf { it.steps })
        chart.axisLeft.axisMaximum = highest * 1.2f
        chart.xAxis.axisMinimum = -0.5f
        chart.xAxis.axisMaximum = days.lastIndex + 0.5f
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun formatSteps(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

    private data class StepDay(val date: LocalDate, val steps: Int)

    private object CompactStepsFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = when {
            value >= 1_000f -> String.format(Locale.getDefault(), "%.0fk", value / 1_000f)
            else -> value.toInt().toString()
        }
    }

    companion object {
        const val EXTRA_CURRENT_STEPS = "current_steps"
        private const val CHART_DAYS = 7
    }
}
