import "server-only";

import { createSupabaseServerClient } from "@/lib/supabase/server";
import { getZonedDateRangeBounds } from "@/lib/dashboard/time-zone";
import {
  getWorkoutAverageHeartRate,
  getWorkoutDurationMs,
  getWorkoutStepCount,
} from "@/lib/dashboard/metrics";
import type { Database } from "@/types/database";
import {
  HISTORY_PAGE_SIZE,
  type AlertHistoryItem,
  type FeedbackHistoryItem,
  type HistoryFilters,
  type HistoryItem,
  type HistoryLoadResult,
  type PredictionHistoryItem,
  type WorkoutHistoryItem,
} from "./types";

type HistoryClient = NonNullable<
  Awaited<ReturnType<typeof createSupabaseServerClient>>
>;
type PredictionRow = Database["public"]["Tables"]["stress_predictions"]["Row"];
type AlertRow = Database["public"]["Tables"]["alert_events"]["Row"];
type FeedbackRow = Database["public"]["Tables"]["stress_feedback"]["Row"];
type WorkoutRow = Database["public"]["Tables"]["workout_sessions"]["Row"];

function predictionItem(row: PredictionRow): PredictionHistoryItem {
  return {
    kind: "prediction",
    id: row.id,
    occurredAt: row.recorded_at,
    label: row.label,
    classIndex: row.class_index,
    confidence: row.confidence,
    heartRate: row.heart_rate,
    sleepHours: row.sleep_hours,
    activityLevel: row.activity_level,
    dailySteps: row.daily_steps,
    modelVersion: row.model_version,
    outOfTrainingRange: row.out_of_training_range,
  };
}

function alertItem(row: AlertRow): AlertHistoryItem {
  return {
    kind: "alert",
    id: row.id,
    occurredAt: row.fired_at,
    reason: row.reason,
    highCountInWindow: row.high_count_in_window,
    windowSize: row.window_size,
    modelVersion: row.model_version,
    dismissed: row.dismissed,
  };
}

function feedbackItem(row: FeedbackRow): FeedbackHistoryItem {
  return {
    kind: "feedback",
    id: row.id,
    occurredAt: row.responded_at,
    alertFiredAt: row.alert_fired_at,
    predictionRecordedAt: row.prediction_recorded_at,
    promptSource: row.prompt_source,
    predictedLabel: row.predicted_label,
    predictedClassIndex: row.predicted_class_index,
    confidence: row.confidence,
    confirmedStressed: row.confirmed_stressed,
    severity: row.severity,
    heartRate: row.heart_rate,
    dailySteps: row.daily_steps,
    activityLevel: row.activity_level,
    sleepHours: row.sleep_hours,
    modelVersion: row.model_version,
    outOfTrainingRange: row.out_of_training_range,
  };
}

function workoutItem(row: WorkoutRow, now: Date): WorkoutHistoryItem {
  return {
    kind: "workout",
    id: row.id,
    occurredAt: row.started_at,
    plannedEndAt: row.planned_end_at,
    endedAt: row.ended_at,
    status: row.status,
    durationMs: getWorkoutDurationMs(row, now),
    stepCount: getWorkoutStepCount(row),
    averageHeartRate: getWorkoutAverageHeartRate(row),
    minHeartRate: row.min_heart_rate,
    maxHeartRate: row.max_heart_rate,
    totalPausedMs: row.total_paused_ms,
  };
}

async function getPredictionPage(
  client: HistoryClient,
  userId: string,
  filters: HistoryFilters,
  start: Date | null,
  end: Date | null,
) {
  const offset = (filters.page - 1) * HISTORY_PAGE_SIZE;
  let query = client
    .from("stress_predictions")
    .select(
      "id,user_id,recorded_at,label,class_index,confidence,probabilities,model_version,heart_rate,daily_steps,activity_level,sleep_hours,out_of_training_range,created_at",
      { count: "exact" },
    )
    .eq("user_id", userId);

  if (start) query = query.gte("recorded_at", start.toISOString());
  if (end) query = query.lt("recorded_at", end.toISOString());
  if (filters.predictionLabel !== "all") {
    query = query.eq("label", filters.predictionLabel);
  }
  if (filters.inputRange !== "all") {
    query = query.eq("out_of_training_range", filters.inputRange === "warning");
  }

  const { data, count, error } = await query
    .order("recorded_at", { ascending: false })
    .range(offset, offset + HISTORY_PAGE_SIZE - 1);

  if (error) throw new Error("Prediction history query failed");
  return { items: (data as PredictionRow[]).map(predictionItem), total: count ?? 0 };
}

async function getAlertTimelinePage(
  client: HistoryClient,
  userId: string,
  filters: HistoryFilters,
  start: Date | null,
  end: Date | null,
) {
  const offset = (filters.page - 1) * HISTORY_PAGE_SIZE;
  const fetchEnd = offset + HISTORY_PAGE_SIZE;
  const includeAlerts = filters.alertRecords !== "feedback";
  const includeFeedback = filters.alertRecords !== "alerts";

  let alertsQuery = client
    .from("alert_events")
    .select(
      "id,user_id,fired_at,reason,high_count_in_window,window_size,model_version,dismissed,created_at",
      { count: "exact" },
    )
    .eq("user_id", userId);
  if (start) alertsQuery = alertsQuery.gte("fired_at", start.toISOString());
  if (end) alertsQuery = alertsQuery.lt("fired_at", end.toISOString());
  if (filters.dismissed !== "all") {
    alertsQuery = alertsQuery.eq("dismissed", filters.dismissed === "yes");
  }

  let feedbackQuery = client
    .from("stress_feedback")
    .select(
      "id,user_id,prompt_source,alert_fired_at,prediction_recorded_at,responded_at,predicted_label,predicted_class_index,confidence,probabilities,model_version,heart_rate,daily_steps,activity_level,sleep_hours,out_of_training_range,profile_age,profile_gender,profile_occupation,profile_bmi,confirmed_stressed,severity,created_at",
      { count: "exact" },
    )
    .eq("user_id", userId);
  if (start) feedbackQuery = feedbackQuery.gte("responded_at", start.toISOString());
  if (end) feedbackQuery = feedbackQuery.lt("responded_at", end.toISOString());
  if (filters.feedbackSource !== "all") {
    feedbackQuery = feedbackQuery.eq("prompt_source", filters.feedbackSource);
  }

  const [alertsResult, feedbackResult] = await Promise.all([
    includeAlerts
      ? alertsQuery.order("fired_at", { ascending: false }).range(0, fetchEnd)
      : Promise.resolve({ data: [], count: 0, error: null }),
    includeFeedback
      ? feedbackQuery.order("responded_at", { ascending: false }).range(0, fetchEnd)
      : Promise.resolve({ data: [], count: 0, error: null }),
  ]);

  if (alertsResult.error || feedbackResult.error) {
    throw new Error("Alert history query failed");
  }

  const merged: HistoryItem[] = [
    ...(alertsResult.data as AlertRow[]).map(alertItem),
    ...(feedbackResult.data as FeedbackRow[]).map(feedbackItem),
  ].sort(
    (left, right) =>
      new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime(),
  );

  return {
    items: merged.slice(offset, offset + HISTORY_PAGE_SIZE),
    total: (alertsResult.count ?? 0) + (feedbackResult.count ?? 0),
  };
}

async function getWorkoutPage(
  client: HistoryClient,
  userId: string,
  filters: HistoryFilters,
  start: Date | null,
  end: Date | null,
  now: Date,
) {
  const offset = (filters.page - 1) * HISTORY_PAGE_SIZE;
  let query = client
    .from("workout_sessions")
    .select(
      "id,user_id,started_at,planned_end_at,ended_at,status,total_paused_ms,first_steps,last_steps,min_heart_rate,max_heart_rate,heart_rate_sum,heart_rate_samples,updated_at,created_at",
      { count: "exact" },
    )
    .eq("user_id", userId);

  if (start) query = query.gte("started_at", start.toISOString());
  if (end) query = query.lt("started_at", end.toISOString());
  if (filters.workoutStatus !== "all") {
    query = query.eq("status", filters.workoutStatus);
  }

  const { data, count, error } = await query
    .order("started_at", { ascending: false })
    .range(offset, offset + HISTORY_PAGE_SIZE - 1);

  if (error) throw new Error("Workout history query failed");
  return {
    items: (data as WorkoutRow[]).map((row) => workoutItem(row, now)),
    total: count ?? 0,
  };
}

export async function getHistoryPage(
  filters: HistoryFilters,
  timeZone: string,
  now: Date,
): Promise<HistoryLoadResult> {
  const client = await createSupabaseServerClient();
  if (!client) return { status: "configuration-error" };

  const {
    data: { user },
  } = await client.auth.getUser();
  if (!user) return { status: "unauthorized" };

  try {
    const { start, end } = getZonedDateRangeBounds(filters.from, filters.to, timeZone);
    const result =
      filters.tab === "predictions"
        ? await getPredictionPage(client, user.id, filters, start, end)
        : filters.tab === "alerts"
          ? await getAlertTimelinePage(client, user.id, filters, start, end)
          : await getWorkoutPage(client, user.id, filters, start, end, now);
    const totalPages = Math.max(1, Math.ceil(result.total / HISTORY_PAGE_SIZE));

    return {
      status: "ready",
      data: {
        tab: filters.tab,
        items: result.items,
        page: filters.page,
        pageSize: HISTORY_PAGE_SIZE,
        total: result.total,
        totalPages,
        hasPrevious: filters.page > 1,
        hasNext: filters.page < totalPages,
      },
    };
  } catch {
    return { status: "query-error" };
  }
}
