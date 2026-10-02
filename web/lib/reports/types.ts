export const REPORT_MAX_RANGE_DAYS = 90;
export const REPORT_MAX_ROWS = 5_000;
export const REPORT_MAX_PAYLOAD_BYTES = 4_500_000;
export const REPORT_URL_LIFETIME_SECONDS = 600;

export const REPORT_CSV_COLUMNS = [
  "record_type",
  "event_time_utc",
  "local_date",
  "timezone",
  "status_or_label",
  "confidence",
  "heart_rate_bpm",
  "sleep_hours",
  "activity_level",
  "steps_snapshot",
  "model_version",
  "out_of_training_range",
  "alert_reason",
  "alert_high_count",
  "alert_window_size",
  "alert_dismissed",
  "feedback_source",
  "feedback_confirmed_stressed",
  "feedback_severity",
  "workout_end_time_utc",
  "workout_duration_seconds",
  "workout_steps",
  "workout_average_heart_rate_bpm",
  "workout_min_heart_rate_bpm",
  "workout_max_heart_rate_bpm",
] as const;

export type ReportCsvColumn = (typeof REPORT_CSV_COLUMNS)[number];
export type ReportCsvValue = string | number | boolean | null;
export type ReportCsvRow = Record<ReportCsvColumn, ReportCsvValue>;

export type ReportLambdaPayload = {
  requestId: string;
  subjectHash: string;
  fromDate: string;
  toDate: string;
  timeZone: string;
  rows: ReportCsvRow[];
};

export type ReportDownload = {
  downloadUrl: string;
  expiresAt: string;
};
