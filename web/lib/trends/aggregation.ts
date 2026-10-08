import { getZonedDateParts, getZonedRangeBounds } from "@/lib/dashboard/time-zone";
import { isStressedPrediction } from "@/lib/dashboard/metrics";
import type {
  DailyTrendPoint,
  TrendPredictionInput,
  TrendRange,
  TrendsDataset,
} from "./types";

// Mirrors StressAlertPolicy.THRESHOLD used by Android's Trends screen.
export const HIGH_STRESS_DAY_THRESHOLD = 3;

function dateKey(date: Date, timeZone: string) {
  const parts = getZonedDateParts(date, timeZone);
  return [
    String(parts.year).padStart(4, "0"),
    String(parts.month).padStart(2, "0"),
    String(parts.day).padStart(2, "0"),
  ].join("-");
}

function dateLabel(value: string, style: "short" | "full") {
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return new Intl.DateTimeFormat("en-US", {
    timeZone: "UTC",
    month: style === "short" ? "short" : "long",
    day: "numeric",
    weekday: style === "full" ? "short" : undefined,
  }).format(date);
}

function validNumbers(values: Array<number | null>) {
  return values.filter((value): value is number =>
    value !== null && Number.isFinite(value),
  );
}

function average(values: Array<number | null>, digits = 1) {
  const valid = validNumbers(values);
  if (valid.length === 0) {
    return null;
  }

  const factor = 10 ** digits;
  return Math.round((valid.reduce((sum, value) => sum + value, 0) / valid.length) * factor) / factor;
}

function averageAcrossDays(values: Array<number | null>, minimumDays = 2) {
  const valid = validNumbers(values);
  return valid.length >= minimumDays ? average(valid, 1) : null;
}

export function parseTrendRange(value: string | string[] | undefined): TrendRange {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate === "30" ? 30 : 7;
}

export function aggregateTrends(
  rows: TrendPredictionInput[],
  range: TrendRange,
  now: Date,
  timeZone: string,
): TrendsDataset {
  const bounds = getZonedRangeBounds(now, timeZone, range);
  const rowsByDate = new Map<string, TrendPredictionInput[]>();

  for (const row of rows) {
    const recordedAt = new Date(row.recorded_at);
    if (!Number.isFinite(recordedAt.getTime())) {
      continue;
    }

    const key = dateKey(recordedAt, timeZone);
    if (!bounds.dateKeys.includes(key)) {
      continue;
    }

    const bucket = rowsByDate.get(key) ?? [];
    bucket.push(row);
    rowsByDate.set(key, bucket);
  }

  const days: DailyTrendPoint[] = bounds.dateKeys.map((key) => {
    const dayRows = rowsByDate.get(key) ?? [];
    if (dayRows.length === 0) {
      return {
        date: key,
        shortLabel: dateLabel(key, "short"),
        fullLabel: dateLabel(key, "full"),
        status: "missing",
        readingCount: null,
        stressedReadingCount: null,
        isHighStressDay: null,
        stressedProportion: null,
        averageHeartRate: null,
        averageSleepHours: null,
        averageActivityLevel: null,
        outOfRangeCount: null,
      };
    }

    const stressedReadingCount = dayRows.filter(isStressedPrediction).length;
    return {
      date: key,
      shortLabel: dateLabel(key, "short"),
      fullLabel: dateLabel(key, "full"),
      status: key === bounds.currentDateKey ? "in-progress" : "observed",
      readingCount: dayRows.length,
      stressedReadingCount,
      isHighStressDay: stressedReadingCount >= HIGH_STRESS_DAY_THRESHOLD,
      stressedProportion: stressedReadingCount / dayRows.length,
      averageHeartRate: average(dayRows.map((row) => row.heart_rate), 0),
      averageSleepHours: average(dayRows.map((row) => row.sleep_hours), 2),
      averageActivityLevel: average(dayRows.map((row) => row.activity_level), 0),
      outOfRangeCount: dayRows.filter((row) => row.out_of_training_range).length,
    };
  });

  const observedDays = days.filter((day) => day.readingCount !== null);
  const allValidRows = Array.from(rowsByDate.values()).flat();
  const latestRecordedAt = allValidRows.reduce<string | null>((latest, row) => {
    if (!latest || new Date(row.recorded_at).getTime() > new Date(latest).getTime()) {
      return row.recorded_at;
    }
    return latest;
  }, null);

  return {
    range,
    timeZone,
    days,
    summary: {
      totalReadings: observedDays.reduce((sum, day) => sum + (day.readingCount ?? 0), 0),
      totalStressedReadings: observedDays.reduce(
        (sum, day) => sum + (day.stressedReadingCount ?? 0),
        0,
      ),
      highStressDays: observedDays.filter((day) => day.isHighStressDay).length,
      daysWithData: observedDays.length,
      missingDays: range - observedDays.length,
      outOfRangeReadings: observedDays.reduce(
        (sum, day) => sum + (day.outOfRangeCount ?? 0),
        0,
      ),
      averageDailyStressedProportion: averageAcrossDays(
        observedDays.map((day) => day.stressedProportion),
      ),
      averageDailyHeartRate: averageAcrossDays(
        observedDays.map((day) => day.averageHeartRate),
      ),
      averageDailySleepHours: averageAcrossDays(
        observedDays.map((day) => day.averageSleepHours),
      ),
      averageDailyActivityLevel: averageAcrossDays(
        observedDays.map((day) => day.averageActivityLevel),
      ),
      heartRateDays: observedDays.filter((day) => day.averageHeartRate !== null).length,
      sleepDays: observedDays.filter((day) => day.averageSleepHours !== null).length,
      activityDays: observedDays.filter((day) => day.averageActivityLevel !== null).length,
      latestRecordedAt,
    },
  };
}
