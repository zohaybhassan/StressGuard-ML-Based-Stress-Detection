package com.example.stressguard.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object SleepRepository {
    const val LOOKBACK_DAYS = 7L

    suspend fun readLatestDay(
        client: HealthConnectClient,
        includeOxygen: Boolean,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SleepDay? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS),
                    now,
                ),
            )
        ).records

        val day = SleepDayAggregator.latest(records.map(::toInterval), zoneId) ?: return null
        if (!includeOxygen) return day

        val oxygen = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    day.intervals.minOf { it.start },
                    day.intervals.maxOf { it.end },
                ),
            )
        ).records.filter { sample ->
            day.intervals.any { sample.time >= it.start && sample.time <= it.end }
        }

        return day.copy(
            averageOxygenPercent = oxygen.map { it.percentage.value }.average().takeUnless { it.isNaN() },
            oxygenSampleCount = oxygen.size,
        )
    }

    private fun toInterval(record: SleepSessionRecord) = SleepInterval(
        start = record.startTime,
        end = record.endTime,
        stages = record.stages.map { stage ->
            SleepStageInterval(
                start = stage.startTime,
                end = stage.endTime,
                type = when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageType.DEEP
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageType.LIGHT
                    SleepSessionRecord.STAGE_TYPE_REM -> SleepStageType.REM
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageType.AWAKE
                    else -> SleepStageType.OTHER
                },
            )
        },
    )
}
