import { getZonedDateParts } from "@/lib/dashboard/time-zone";
import {
  getWorkoutAverageHeartRate,
  getWorkoutDurationMs,
  getWorkoutStepCount,
} from "@/lib/dashboard/metrics";
import type { Database } from "@/types/database";
import type { ReportCsvRow } from "./types";

type PredictionRow = Database["public"]["Tables"]["stress_predictions"]["Row"];
type AlertRow = Database["public"]["Tables"]["alert_events"]["Row"];
type FeedbackRow = Database["public"]["Tables"]["stress_feedback"]["Row"];
type WorkoutRow = Database["public"]["Tables"]["workout_sessions"]["Row"];

export type ReportPrediction = Pick<
  PredictionRow,
  | "recorded_at"
  | "label"
  | "confidence"
  | "heart_rate"
  | "sleep_hours"
  | "activity_level"
  | "daily_steps"
  | "model_version"
  | "out_of_training_range"
>;
export type ReportAlert = Pick<
  AlertRow,
  | "fired_at"
  | "dismissed"
  | "model_version"
  | "reason"
  | "high_count_in_window"
  | "window_size"
>;
export type ReportFeedback = Pick<
  FeedbackRow,
  | "responded_at"
  | "predicted_label"
  | "confidence"
  | "heart_rate"
  | "sleep_hours"
  | "activity_level"
  | "daily_steps"
  | "model_version"
  | "out_of_training_range"
  | "prompt_source"
  | "confirmed_stressed"
  | "severity"
>;
export type ReportWorkout = Pick<
  WorkoutRow,
  | "started_at"
  | "planned_end_at"
  | "ended_at"
  | "status"
  | "total_paused_ms"
  | "first_steps"
  | "last_steps"
  | "min_heart_rate"
  | "max_heart_rate"
  | "heart_rate_sum"
  | "heart_rate_samples"
  | "updated_at"
>;

function localDate(value: string, timeZone: string) {
  const parts = getZonedDateParts(new Date(value), timeZone);
  return [
    String(parts.year).padStart(4, "0"),
    String(parts.month).padStart(2, "0"),
    String(parts.day).padStart(2, "0"),
  ].join("-");
}

function blankRow(
  recordType: ReportCsvRow["record_type"],
  eventTime: string,
  timeZone: string,
): ReportCsvRow {
  return {
    record_type: recordType,
    event_time_utc: eventTime,
    local_date: localDate(eventTime, timeZone),
    timezone: timeZone,
    status_or_label: null,
    confidence: null,
    heart_rate_bpm: null,
    sleep_hours: null,
    activity_level: null,
    steps_snapshot: null,
    model_version: null,
    out_of_training_range: null,
    alert_reason: null,
    alert_high_count: null,
    alert_window_size: null,
    alert_dismissed: null,
    feedback_source: null,
    feedback_confirmed_stressed: null,
    feedback_severity: null,
    workout_end_time_utc: null,
    workout_duration_seconds: null,
    workout_steps: null,
    workout_average_heart_rate_bpm: null,
    workout_min_heart_rate_bpm: null,
    workout_max_heart_rate_bpm: null,
  };
}

export function normalizeReportRows(
  data: {
    predictions: ReportPrediction[];
    alerts: ReportAlert[];
    feedback: ReportFeedback[];
    workouts: ReportWorkout[];
  },
  timeZone: string,
  now: Date,
) {
  const rows: ReportCsvRow[] = [
    ...data.predictions.map((item) => ({
      ...blankRow("prediction", item.recorded_at, timeZone),
      status_or_label: item.label,
      confidence: item.confidence,
      heart_rate_bpm: item.heart_rate,
      sleep_hours: item.sleep_hours,
      activity_level: item.activity_level,
      steps_snapshot: item.daily_steps,
      model_version: item.model_version,
      out_of_training_range: item.out_of_training_range,
    })),
    ...data.alerts.map((item) => ({
      ...blankRow("alert", item.fired_at, timeZone),
      status_or_label: item.dismissed ? "dismissed" : "recorded",
      model_version: item.model_version,
      alert_reason: item.reason,
      alert_high_count: item.high_count_in_window,
      alert_window_size: item.window_size,
      alert_dismissed: item.dismissed,
    })),
    ...data.feedback.map((item) => ({
      ...blankRow("feedback", item.responded_at, timeZone),
      status_or_label: item.predicted_label,
      confidence: item.confidence,
      heart_rate_bpm: item.heart_rate,
      sleep_hours: item.sleep_hours,
      activity_level: item.activity_level,
      steps_snapshot: item.daily_steps,
      model_version: item.model_version,
      out_of_training_range: item.out_of_training_range,
      feedback_source: item.prompt_source,
      feedback_confirmed_stressed: item.confirmed_stressed,
      feedback_severity: item.severity,
    })),
    ...data.workouts.map((item) => {
      const duration = getWorkoutDurationMs(item, now);
      return {
        ...blankRow("workout", item.started_at, timeZone),
        status_or_label: item.status,
        workout_end_time_utc: item.ended_at,
        workout_duration_seconds: duration === null ? null : Math.round(duration / 1_000),
        workout_steps: getWorkoutStepCount(item),
        workout_average_heart_rate_bpm: getWorkoutAverageHeartRate(item),
        workout_min_heart_rate_bpm: item.min_heart_rate,
        workout_max_heart_rate_bpm: item.max_heart_rate,
      };
    }),
  ];

  return rows.sort(
    (left, right) =>
      new Date(left.event_time_utc as string).getTime() -
      new Date(right.event_time_utc as string).getTime(),
  );
}
