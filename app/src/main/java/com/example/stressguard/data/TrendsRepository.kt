package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.StressModelInfo
import com.example.stressguard.data.local.StressGuardDatabase
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * What the trends screen draws.
 *
 * [days] runs oldest to newest — the direction a chart's x-axis reads — which is the reverse of
 * [StressHistory.summarise]'s newest-first order. Flipped once here rather than at each chart, so
 * the three of them cannot disagree about which end of the week they are showing.
 */
data class StressTrends(
    val days: List<DailyStressSummary>,
    /** High-stress readings so far today, which is a live figure rather than a daily rollup. */
    val highStressReadingsToday: Int,
    val readingsToday: Int,
) {
    val hasData: Boolean get() = days.isNotEmpty()

    /** Days in the window that carried at least one reading. Not the same as the window length. */
    val daysWithData: Int get() = days.size

    val highStressDays: Int get() = StressHistory.highStressDayCount(days)

    /**
     * True when one day holds every reading.
     *
     * A single-day chart is a dot, not a trend, so the screen says so rather than drawing something
     * that implies a week of evidence. Distinct from [hasData]: there *is* data, just not enough of
     * it spread over time to mean anything.
     */
    val isTooSparseToChart: Boolean get() = days.size < 2

    companion object {
        val EMPTY = StressTrends(emptyList(), 0, 0)
    }
}

/**
 * Reads the stored prediction history for the trends screen.
 *
 * No new storage and no new queries: this is the same per-day rollup the recommendation already
 * runs, over the same rows, so the chart and the risk score cannot disagree about what counted as a
 * high-stress day. That shared definition is the reason [StressHistory] is a pure function over
 * rows rather than something each caller reimplements.
 *
 * Local only, like everything else on the read path — the charts render in airplane mode.
 */
object TrendsRepository {

    private const val TAG = "STRESS_TRENDS"

    /** Plan §17 asks for a weekly trend. Seven days is what the recommendation's first tier uses. */
    const val WINDOW_DAYS = 7

    private val WINDOW_MS = TimeUnit.DAYS.toMillis(WINDOW_DAYS.toLong())

    suspend fun load(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): StressTrends = runCatching {
        val database = StressGuardDatabase.get(context)

        // From the manifest rather than hardcoded: the shipped bundle is binary, but the three-level
        // one is swappable and there the high class is index 2.
        val highStressClassIndex = StressModelInfo.fromAssets(context).classCount - 1

        val rows = database.stressPredictions().since(nowEpochMs - WINDOW_MS)
        val summaries = StressHistory.summarise(rows, highStressClassIndex, timeZone)

        // Today is counted from the rows rather than taken from the summary, because a day that has
        // not yet reached the high-stress threshold still has readings worth showing — and the
        // headline is "how today is going", not "did today qualify".
        val todayKey = StepHistory.dateKey(nowEpochMs, timeZone)
        val today = summaries.firstOrNull { it.date == todayKey }

        StressTrends(
            days = summaries.sortedBy { it.date },
            highStressReadingsToday = today?.highStressReadings ?: 0,
            readingsToday = today?.readings ?: 0,
        )
    }.onFailure {
        Log.w(TAG, "could not load trends", it)
    }.getOrDefault(StressTrends.EMPTY)
}
