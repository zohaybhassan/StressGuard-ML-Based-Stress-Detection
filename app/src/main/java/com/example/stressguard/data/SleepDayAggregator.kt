package com.example.stressguard.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SleepInterval(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStageInterval> = emptyList(),
)

data class SleepStageInterval(
    val start: Instant,
    val end: Instant,
    val type: SleepStageType,
)

enum class SleepStageType { DEEP, LIGHT, REM, AWAKE, OTHER }

data class SleepPeriod(
    val start: Instant,
    val end: Instant,
    val duration: Duration,
)

data class SleepStageTotals(
    val deep: Duration = Duration.ZERO,
    val light: Duration = Duration.ZERO,
    val rem: Duration = Duration.ZERO,
    val awake: Duration = Duration.ZERO,
    val other: Duration = Duration.ZERO,
)

data class SleepDay(
    val date: LocalDate,
    val mainSleep: SleepPeriod,
    val naps: List<SleepPeriod>,
    val totalDuration: Duration,
    val stages: SleepStageTotals,
    val intervals: List<SleepInterval>,
    val averageOxygenPercent: Double? = null,
    val oxygenSampleCount: Int = 0,
)

/** Turns provider records into the latest local sleep day used by the model and sleep screen. */
object SleepDayAggregator {
    private val fragmentGap: Duration = Duration.ofHours(2)

    fun latest(intervals: List<SleepInterval>, zoneId: ZoneId): SleepDay? {
        val valid = intervals.filter { it.end.isAfter(it.start) }
        if (valid.isEmpty()) return null

        // A night is assigned to the date on which the person wakes. Naps ending on that same
        // date therefore augment the night instead of replacing it when they arrive later.
        val latestDate = valid.maxOf { it.end.atZone(zoneId).toLocalDate() }
        val dayIntervals = recordsForSleepDay(valid, latestDate, zoneId)

        val clusters = cluster(dayIntervals)
        val periods = clusters.map(::toPeriod)
        val mainIndex = periods.indices.maxBy { periods[it].duration }
        val mainSleep = periods[mainIndex]
        val naps = periods.filterIndexed { index, _ -> index != mainIndex }.sortedBy { it.start }

        return SleepDay(
            date = latestDate,
            mainSleep = mainSleep,
            naps = naps,
            totalDuration = unionDuration(dayIntervals.map { it.start to it.end }),
            stages = stageTotals(dayIntervals.flatMap { it.stages }),
            intervals = dayIntervals,
        )
    }

    private fun recordsForSleepDay(
        intervals: List<SleepInterval>,
        date: LocalDate,
        zoneId: ZoneId,
    ): List<SleepInterval> {
        val selected = intervals
            .filter { it.end.atZone(zoneId).toLocalDate() == date }
            .toMutableList()
        val remaining = intervals.filterNot { it in selected }.toMutableList()

        // A provider can split a night around midnight. Pull adjacent earlier fragments into the
        // wake date recursively, while the short gap prevents this from reaching yesterday's nap.
        var changed: Boolean
        do {
            changed = false
            val adjacent = remaining.filter { candidate ->
                selected.any { current ->
                    !candidate.start.isAfter(current.end.plus(fragmentGap)) &&
                        !current.start.isAfter(candidate.end.plus(fragmentGap))
                }
            }
            if (adjacent.isNotEmpty()) {
                selected += adjacent
                remaining -= adjacent.toSet()
                changed = true
            }
        } while (changed)

        return selected.sortedBy { it.start }
    }

    private fun cluster(intervals: List<SleepInterval>): List<List<SleepInterval>> {
        val result = mutableListOf<MutableList<SleepInterval>>()
        for (interval in intervals) {
            val current = result.lastOrNull()
            val currentEnd = current?.maxOfOrNull { it.end }
            if (current == null || currentEnd == null ||
                interval.start.isAfter(currentEnd.plus(fragmentGap))
            ) {
                result += mutableListOf(interval)
            } else {
                current += interval
            }
        }
        return result
    }

    private fun toPeriod(intervals: List<SleepInterval>) = SleepPeriod(
        start = intervals.minOf { it.start },
        end = intervals.maxOf { it.end },
        duration = unionDuration(intervals.map { it.start to it.end }),
    )

    private fun stageTotals(stages: List<SleepStageInterval>): SleepStageTotals {
        fun total(type: SleepStageType) = unionDuration(
            stages.filter { it.type == type }.map { it.start to it.end }
        )
        return SleepStageTotals(
            deep = total(SleepStageType.DEEP),
            light = total(SleepStageType.LIGHT),
            rem = total(SleepStageType.REM),
            awake = total(SleepStageType.AWAKE),
            other = total(SleepStageType.OTHER),
        )
    }

    /** Counts overlapping provider records once, which also protects against duplicate syncs. */
    private fun unionDuration(ranges: List<Pair<Instant, Instant>>): Duration {
        val sorted = ranges.filter { it.second.isAfter(it.first) }.sortedBy { it.first }
        if (sorted.isEmpty()) return Duration.ZERO

        var start = sorted.first().first
        var end = sorted.first().second
        var total = Duration.ZERO
        for ((nextStart, nextEnd) in sorted.drop(1)) {
            if (!nextStart.isAfter(end)) {
                if (nextEnd.isAfter(end)) end = nextEnd
            } else {
                total = total.plus(Duration.between(start, end))
                start = nextStart
                end = nextEnd
            }
        }
        return total.plus(Duration.between(start, end))
    }
}
