import "server-only";

import type { SupabaseClient } from "@supabase/supabase-js";
import type { Database } from "@/types/database";
import type {
  ReportAlert,
  ReportFeedback,
  ReportPrediction,
  ReportWorkout,
} from "./normalization";
import { enforceReportRowLimit } from "./limits";

type Client = SupabaseClient<Database>;

export async function getReportData(
  client: Client,
  userId: string,
  start: Date,
  end: Date,
) {
  const startIso = start.toISOString();
  const endIso = end.toISOString();
  const countQueries = [
    client.from("stress_predictions").select("id", { count: "exact", head: true }).eq("user_id", userId).gte("recorded_at", startIso).lt("recorded_at", endIso),
    client.from("alert_events").select("id", { count: "exact", head: true }).eq("user_id", userId).gte("fired_at", startIso).lt("fired_at", endIso),
    client.from("stress_feedback").select("id", { count: "exact", head: true }).eq("user_id", userId).gte("responded_at", startIso).lt("responded_at", endIso),
    client.from("workout_sessions").select("id", { count: "exact", head: true }).eq("user_id", userId).gte("started_at", startIso).lt("started_at", endIso),
  ];
  const counts = await Promise.all(countQueries);
  if (counts.some((result) => result.error)) throw new Error("Report count query failed");
  enforceReportRowLimit(counts.map((result) => result.count ?? 0));

  const [predictions, alerts, feedback, workouts] = await Promise.all([
    client.from("stress_predictions").select("recorded_at,label,confidence,heart_rate,sleep_hours,activity_level,daily_steps,model_version,out_of_training_range").eq("user_id", userId).gte("recorded_at", startIso).lt("recorded_at", endIso).order("recorded_at"),
    client.from("alert_events").select("fired_at,dismissed,model_version,reason,high_count_in_window,window_size").eq("user_id", userId).gte("fired_at", startIso).lt("fired_at", endIso).order("fired_at"),
    client.from("stress_feedback").select("responded_at,predicted_label,confidence,heart_rate,sleep_hours,activity_level,daily_steps,model_version,out_of_training_range,prompt_source,confirmed_stressed,severity").eq("user_id", userId).gte("responded_at", startIso).lt("responded_at", endIso).order("responded_at"),
    client.from("workout_sessions").select("started_at,planned_end_at,ended_at,status,total_paused_ms,first_steps,last_steps,min_heart_rate,max_heart_rate,heart_rate_sum,heart_rate_samples,updated_at").eq("user_id", userId).gte("started_at", startIso).lt("started_at", endIso).order("started_at"),
  ]);
  if (predictions.error || alerts.error || feedback.error || workouts.error) {
    throw new Error("Report data query failed");
  }

  return {
    predictions: predictions.data as ReportPrediction[],
    alerts: alerts.data as ReportAlert[],
    feedback: feedback.data as ReportFeedback[],
    workouts: workouts.data as ReportWorkout[],
  };
}
