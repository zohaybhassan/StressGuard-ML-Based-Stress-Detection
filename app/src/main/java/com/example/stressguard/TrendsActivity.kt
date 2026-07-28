package com.example.stressguard

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.DailyStressSummary
import com.example.stressguard.data.StressAlertPolicy
import com.example.stressguard.data.StressTrends
import com.example.stressguard.data.TrendsRepository
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The stored prediction history, drawn.
 *
 * Reads the same per-day rollup the recommendation uses ([com.example.stressguard.data.StressHistory]),
 * so the chart and the risk score cannot disagree about what counted as a high-stress day — the
 * alternative, each computing its own, is how a dashboard ends up contradicting its own advice.
 *
 * Everything here is local, so the screen renders in airplane mode like the rest of the read path.
 */
class TrendsActivity : AppCompatActivity() {

    private lateinit var tvTodayHighStress: TextView
    private lateinit var tvTodaySummary: TextView
    private lateinit var tvWeekSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chartGroup: View
    private lateinit var chartStress: BarChart
    private lateinit var chartVitals: LineChart
    private lateinit var chartActivity: LineChart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trends)

        tvTodayHighStress = findViewById(R.id.tvTodayHighStress)
        tvTodaySummary = findViewById(R.id.tvTodaySummary)
        tvWeekSummary = findViewById(R.id.tvWeekSummary)
        tvEmpty = findViewById(R.id.tvTrendsEmpty)
        chartGroup = findViewById(R.id.chartGroup)
        chartStress = findViewById(R.id.chartStress)
        chartVitals = findViewById(R.id.chartVitals)
        chartActivity = findViewById(R.id.chartActivity)

        BottomNav.wire(this, findViewById<BottomNavigationView>(R.id.bottomNavigation), R.id.nav_trends)
    }

    /**
     * Reloaded on resume rather than once in onCreate.
     *
     * Readings arrive in the background while this screen sits in the back stack, so a chart drawn
     * at launch is stale by the time the user returns to it.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { render(TrendsRepository.load(this@TrendsActivity)) }
    }

    private fun render(trends: StressTrends) {
        renderHeadline(trends)

        // "Nothing yet" and "one day so far" are both routine with passive collection, and neither
        // is worth drawing: a single-day chart is a dot that implies a week of evidence.
        if (trends.isTooSparseToChart) {
            chartGroup.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (!trends.hasData) {
                "No readings stored yet.\nWear your watch for a day and your trend will appear here."
            } else {
                "Only one day of readings so far.\nA trend needs at least two."
            }
            return
        }

        tvEmpty.visibility = View.GONE
        chartGroup.visibility = View.VISIBLE

        renderStressChart(trends.days)
        renderVitalsChart(trends.days)
        renderActivityChart(trends.days)
    }

    private fun renderHeadline(trends: StressTrends) {
        tvTodayHighStress.text = trends.highStressReadingsToday.toString()
        tvTodayHighStress.setTextColor(
            if (trends.highStressReadingsToday >= StressAlertPolicy.THRESHOLD) HIGH else NORMAL
        )

        tvTodaySummary.text = when {
            trends.readingsToday == 0 -> "high-stress readings — nothing recorded yet today"
            else -> "of ${trends.readingsToday} readings today were high stress"
        }

        // Says how many days it is speaking for. A "3 high-stress days" figure means something
        // different over three days of data than over seven, and the user cannot tell which
        // without being told.
        tvWeekSummary.text = if (!trends.hasData) {
            "No readings in the last ${TrendsRepository.WINDOW_DAYS} days."
        } else {
            "${trends.highStressDays} high-stress ${plural(trends.highStressDays, "day", "days")} " +
                "across ${trends.daysWithData} ${plural(trends.daysWithData, "day", "days")} with " +
                "readings, in the last ${TrendsRepository.WINDOW_DAYS} days."
        }
    }

    /**
     * High-stress readings per day, with a line at the threshold that makes a day count.
     *
     * The bar's colour and the limit line encode the same rule the alerts and the risk score use, so
     * a user can see why a day did or did not qualify rather than being told the verdict alone.
     */
    private fun renderStressChart(days: List<DailyStressSummary>) {
        val entries = days.mapIndexed { index, day ->
            BarEntry(index.toFloat(), day.highStressReadings.toFloat())
        }

        val set = BarDataSet(entries, "High-stress readings").apply {
            colors = days.map { if (it.isHighStressDay) HIGH else NORMAL }
            setDrawValues(true)
            valueTextSize = 10f
            valueTextColor = AXIS_TEXT
            valueFormatter = IntFormatter
        }

        chartStress.apply {
            data = BarData(set).apply { barWidth = 0.5f }
            styleCommon(days)
            axisLeft.apply {
                removeAllLimitLines()
                axisMinimum = 0f
                granularity = 1f
                addLimitLine(
                    LimitLine(
                        StressAlertPolicy.THRESHOLD.toFloat(),
                        "counts as a high-stress day",
                    ).apply {
                        lineColor = HIGH
                        lineWidth = 1f
                        textColor = AXIS_TEXT
                        textSize = 9f
                        enableDashedLine(8f, 6f, 0f)
                    }
                )
            }
            legend.isEnabled = false
            invalidate()
        }
    }

    /**
     * Heart rate and sleep on one chart, on two axes.
     *
     * They share a shape — both are daily averages of what the model was fed — but not a scale:
     * bpm sits near 70 and sleep near 7.5, so a single axis would flatten sleep into the x-axis.
     */
    private fun renderVitalsChart(days: List<DailyStressSummary>) {
        val heartRate = LineDataSet(
            days.mapIndexed { index, day -> Entry(index.toFloat(), day.averageHeartRate.toFloat()) },
            "Heart rate (bpm)",
        ).apply {
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            color = HEART
            setCircleColor(HEART)
            lineWidth = 2f
            circleRadius = 3.5f
            setDrawCircleHole(false)
            setDrawValues(false)
        }

        val sleep = LineDataSet(
            days.mapIndexed { index, day -> Entry(index.toFloat(), day.averageSleepHours) },
            "Sleep (hours)",
        ).apply {
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
            color = SLEEP
            setCircleColor(SLEEP)
            lineWidth = 2f
            circleRadius = 3.5f
            setDrawCircleHole(false)
            setDrawValues(false)
        }

        chartVitals.apply {
            data = LineData(heartRate, sleep)
            styleCommon(days)
            axisLeft.apply {
                removeAllLimitLines()
                resetAxisMinimum()
                resetAxisMaximum()
            }
            axisRight.apply {
                isEnabled = true
                setDrawGridLines(false)
                textColor = AXIS_TEXT
                textSize = 10f
                axisMinimum = 0f
            }
            legend.apply {
                isEnabled = true
                textColor = AXIS_TEXT
                textSize = 11f
                horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            }
            invalidate()
        }
    }

    private fun renderActivityChart(days: List<DailyStressSummary>) {
        val set = LineDataSet(
            days.mapIndexed { index, day -> Entry(index.toFloat(), day.averageActivityLevel.toFloat()) },
            "Activity level (steps)",
        ).apply {
            color = ACTIVITY
            setCircleColor(ACTIVITY)
            lineWidth = 2f
            circleRadius = 3.5f
            setDrawCircleHole(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = ACTIVITY
            fillAlpha = 40
        }

        chartActivity.apply {
            data = LineData(set)
            styleCommon(days)
            axisLeft.apply {
                removeAllLimitLines()
                axisMinimum = 0f
                resetAxisMaximum()
            }
            legend.isEnabled = false
            invalidate()
        }
    }

    /** Chrome shared by all three charts, so they read as one screen rather than three widgets. */
    private fun com.github.mikephil.charting.charts.BarLineChartBase<*>.styleCommon(
        days: List<DailyStressSummary>,
    ) {
        description.isEnabled = false
        setNoDataText("")
        setScaleEnabled(false)
        isDoubleTapToZoomEnabled = false
        setDrawGridBackground(false)
        setExtraOffsets(4f, 8f, 4f, 8f)

        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            textColor = AXIS_TEXT
            textSize = 10f
            valueFormatter = DayLabelFormatter(days)
            // Half a slot of padding either side, or the first and last bars are clipped by the
            // chart edge.
            axisMinimum = -0.5f
            axisMaximum = days.size - 0.5f
        }

        axisLeft.apply {
            setDrawAxisLine(false)
            gridColor = GRID
            textColor = AXIS_TEXT
            textSize = 10f
        }

        // Off unless a chart turns it back on; only the dual-axis vitals chart wants it.
        axisRight.isEnabled = false
    }

    /** Renders the `yyyy-MM-dd` keys as weekday initials, which is all a 7-slot axis has room for. */
    private class DayLabelFormatter(private val days: List<DailyStressSummary>) : ValueFormatter() {
        private val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val label = SimpleDateFormat("EEE", Locale.getDefault())

        override fun getFormattedValue(value: Float): String {
            val day = days.getOrNull(value.toInt()) ?: return ""
            return runCatching { label.format(parser.parse(day.date)!!) }
                .getOrDefault(day.date.takeLast(5))
        }
    }

    private object IntFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String =
            if (value <= 0f) "" else value.toInt().toString()
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many

    private companion object {
        val HIGH = Color.parseColor("#F44336")
        val NORMAL = Color.parseColor("#69D18F")
        val HEART = Color.parseColor("#E5556E")
        val SLEEP = Color.parseColor("#6250A4")
        val ACTIVITY = Color.parseColor("#0B57D0")
        val AXIS_TEXT = Color.parseColor("#64706A")
        val GRID = Color.parseColor("#E0E7E2")
    }
}
