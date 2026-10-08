import type { Database } from "@/types/database";

export const FRESHNESS_THRESHOLD_MINUTES = 10;

type Prediction = Database["public"]["Tables"]["stress_predictions"]["Row"];
type Workout = Database["public"]["Tables"]["workout_sessions"]["Row"];
type WorkoutTiming = Pick<
  Workout,
  "started_at" | "ended_at" | "status" | "updated_at" | "planned_end_at" | "total_paused_ms"
>;
type WorkoutSteps = Pick<Workout, "first_steps" | "last_steps">;
type WorkoutHeartRate = Pick<Workout, "heart_rate_samples" | "heart_rate_sum">;

export type ReadingFreshness = "fresh" | "delayed" | "unavailable";

export type TodaySummary = {
  readingCount: number;
  stressedCount: number;
  stressedProportion: number | null;
  latestRecordedAt: string | null;
};

export function classifyReadingFreshness(
  recordedAt: string | null | undefined,
  now: Date,
): ReadingFreshness {
  if (!recordedAt) {
    return "unavailable";
  }

  const timestamp = new Date(recordedAt).getTime();
  if (!Number.isFinite(timestamp)) {
    return "unavailable";
  }

  const ageMinutes = Math.max(0, now.getTime() - timestamp) / 60_000;
  return ageMinutes <= FRESHNESS_THRESHOLD_MINUTES ? "fresh" : "delayed";
}

export function isStressedPrediction(
  prediction: Pick<Prediction, "label" | "class_index">,
) {
  const normalizedLabel = prediction.label
    .trim()
    .toLowerCase()
    .replaceAll("-", "_")
    .replaceAll(" ", "_");

  // The shipped binary model uses `stressed`; this also supports the earlier
  // high-stress label without treating every non-zero class as stressed.
  return normalizedLabel === "stressed" || normalizedLabel === "high_stress";
}

export function summarizeToday(
  predictions: Array<Pick<Prediction, "recorded_at" | "label" | "class_index">>,
): TodaySummary {
  const stressedCount = predictions.filter(isStressedPrediction).length;
  const latestRecordedAt = predictions.reduce<string | null>((latest, prediction) => {
    if (!latest) {
      return prediction.recorded_at;
    }

    return new Date(prediction.recorded_at).getTime() > new Date(latest).getTime()
      ? prediction.recorded_at
      : latest;
  }, null);

  return {
    readingCount: predictions.length,
    stressedCount,
    stressedProportion:
      predictions.length > 0 ? stressedCount / predictions.length : null,
    latestRecordedAt,
  };
}

export function getWorkoutDurationMs(workout: WorkoutTiming, now: Date) {
  const startedAt = new Date(workout.started_at).getTime();
  let effectiveEnd: number;

  if (workout.ended_at) {
    effectiveEnd = new Date(workout.ended_at).getTime();
  } else if (workout.status === "paused") {
    effectiveEnd = new Date(workout.updated_at).getTime();
  } else {
    effectiveEnd = Math.min(
      now.getTime(),
      new Date(workout.planned_end_at).getTime(),
    );
  }

  if (!Number.isFinite(startedAt) || !Number.isFinite(effectiveEnd)) {
    return null;
  }

  return Math.max(0, effectiveEnd - startedAt - workout.total_paused_ms);
}

export function getWorkoutStepCount(workout: WorkoutSteps) {
  if (workout.first_steps === null || workout.last_steps === null) {
    return null;
  }

  return Math.max(0, workout.last_steps - workout.first_steps);
}

export function getWorkoutAverageHeartRate(workout: WorkoutHeartRate) {
  if (workout.heart_rate_samples <= 0) {
    return null;
  }

  const average = workout.heart_rate_sum / workout.heart_rate_samples;
  return average >= 30 && average <= 220 ? average : null;
}
