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
const REPORT_FETCH_PAGE_SIZE = 1_000;

async function fetchRows<T>(
  count: number,
  queryPage: (from: number, to: number) => PromiseLike<{ data: T[] | null; error: unknown }>,
): Promise<T[]> {
  const rows: T[] = [];
  for (let from = 0; from < count; from += REPORT_FETCH_PAGE_SIZE) {
    const to = Math.min(from + REPORT_FETCH_PAGE_SIZE, count) - 1;
    const result = await queryPage(from, to);
    if (result.error || !result.data || result.data.length !== to - from + 1) {
      throw new Error("Report data query failed");
    }
    rows.push(...result.data);
  }
  return rows;
}

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
    fetchRows<ReportPrediction>(counts[0].count ?? 0, (from, to) =>
      client.from("stress_predictions").select("recorded_at,label,confidence,heart_rate,sleep_hours,activity_level,daily_steps,model_version,out_of_training_range").eq("user_id", userId).gte("recorded_at", startIso).lt("recorded_at", endIso).order("recorded_at").range(from, to)),
    fetchRows<ReportAlert>(counts[1].count ?? 0, (from, to) =>
      client.from("alert_events").select("fired_at,dismissed,model_version,reason,high_count_in_window,window_size").eq("user_id", userId).gte("fired_at", startIso).lt("fired_at", endIso).order("fired_at").range(from, to)),
    fetchRows<ReportFeedback>(counts[2].count ?? 0, (from, to) =>
      client.from("stress_feedback").select("responded_at,predicted_label,confidence,heart_rate,sleep_hours,activity_level,daily_steps,model_version,out_of_training_range,prompt_source,confirmed_stressed,severity").eq("user_id", userId).gte("responded_at", startIso).lt("responded_at", endIso).order("responded_at").range(from, to)),
    fetchRows<ReportWorkout>(counts[3].count ?? 0, (from, to) =>
      client.from("workout_sessions").select("started_at,planned_end_at,ended_at,status,total_paused_ms,first_steps,last_steps,min_heart_rate,max_heart_rate,heart_rate_sum,heart_rate_samples,updated_at").eq("user_id", userId).gte("started_at", startIso).lt("started_at", endIso).order("started_at").range(from, to)),
  ]);

  return {
    predictions,
    alerts,
    feedback,
    workouts,
  };
}
