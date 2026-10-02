export const TREND_RANGES = [7, 30] as const;
export type TrendRange = (typeof TREND_RANGES)[number];

export type TrendPredictionInput = {
  recorded_at: string;
  label: string;
  class_index: number;
  confidence: number | null;
  model_version: string;
  heart_rate: number | null;
  daily_steps: number | null;
  activity_level: number | null;
  sleep_hours: number | null;
  out_of_training_range: boolean;
};

export type TrendDayStatus = "observed" | "in-progress" | "missing";

export type DailyTrendPoint = {
  date: string;
  shortLabel: string;
  fullLabel: string;
  status: TrendDayStatus;
  readingCount: number | null;
  stressedReadingCount: number | null;
  stressedProportion: number | null;
  averageHeartRate: number | null;
  averageSleepHours: number | null;
  averageActivityLevel: number | null;
  averageDailyStepsSnapshot: number | null;
  averageConfidence: number | null;
  outOfRangeCount: number | null;
};

export type TrendSummary = {
  totalReadings: number;
  totalStressedReadings: number;
  daysWithData: number;
  missingDays: number;
  outOfRangeReadings: number;
  averageDailyStressedProportion: number | null;
  averageDailyHeartRate: number | null;
  averageDailySleepHours: number | null;
  averageDailyActivityLevel: number | null;
  heartRateDays: number;
  sleepDays: number;
  activityDays: number;
  latestRecordedAt: string | null;
  modelVersions: string[];
};

export type TrendsDataset = {
  range: TrendRange;
  timeZone: string;
  days: DailyTrendPoint[];
  summary: TrendSummary;
};

export type TrendsLoadResult =
  | { status: "ready"; dataset: TrendsDataset }
  | { status: "unauthorized" }
  | { status: "configuration-error" }
  | { status: "query-error" };
