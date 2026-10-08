import "server-only";

import { createSupabaseServerClient } from "@/lib/supabase/server";
import { classifyReadingFreshness, summarizeToday } from "./metrics";
import { getZonedDayBounds } from "./time-zone";
import type {
  DashboardLoadResult,
  DashboardSection,
  LatestPrediction,
  LatestWorkout,
  ProfileSummary,
  RecentAlert,
} from "./types";

type DashboardClient = NonNullable<
  Awaited<ReturnType<typeof createSupabaseServerClient>>
>;

class DashboardQueryError extends Error {
  constructor(readonly section: DashboardSection) {
    super(`Dashboard query failed for ${section}`);
    this.name = "DashboardQueryError";
  }
}

function assertQuery<T>(
  section: DashboardSection,
  result: { data: T; error: unknown },
) {
  if (result.error) {
    throw new DashboardQueryError(section);
  }

  return result.data;
}

export async function getDashboardProfile(
  client: DashboardClient,
  userId: string,
) {
  const result = await client
    .from("profiles")
    .select("display_name,email,avatar_url")
    .eq("id", userId)
    .maybeSingle();

  return assertQuery("profile", result) as ProfileSummary | null;
}

export async function getLatestPrediction(
  client: DashboardClient,
  userId: string,
) {
  const result = await client
    .from("stress_predictions")
    .select(
      "id,user_id,recorded_at,label,class_index,confidence,probabilities,model_version,heart_rate,daily_steps,activity_level,sleep_hours,out_of_training_range,created_at",
    )
    .eq("user_id", userId)
    .order("recorded_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  return assertQuery("latest-prediction", result) as LatestPrediction | null;
}

export async function getTodayPredictions(
  client: DashboardClient,
  userId: string,
  start: Date,
  end: Date,
) {
  const result = await client
    .from("stress_predictions")
    .select("recorded_at,label,class_index")
    .eq("user_id", userId)
    .gte("recorded_at", start.toISOString())
    .lt("recorded_at", end.toISOString())
    .order("recorded_at", { ascending: false });

  return assertQuery("today-summary", result);
}

export async function getRecentAlerts(
  client: DashboardClient,
  userId: string,
  limit = 3,
) {
  const result = await client
    .from("alert_events")
    .select(
      "id,user_id,fired_at,reason,high_count_in_window,window_size,model_version,dismissed,created_at",
    )
    .eq("user_id", userId)
    .order("fired_at", { ascending: false })
    .limit(limit);

  return assertQuery("recent-alerts", result) as RecentAlert[];
}

export async function getLatestWorkout(
  client: DashboardClient,
  userId: string,
) {
  const result = await client
    .from("workout_sessions")
    .select(
      "id,user_id,started_at,planned_end_at,ended_at,status,total_paused_ms,first_steps,last_steps,min_heart_rate,max_heart_rate,heart_rate_sum,heart_rate_samples,updated_at,created_at",
    )
    .eq("user_id", userId)
    .order("started_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  return assertQuery("latest-workout", result) as LatestWorkout | null;
}

type Captured<T> = {
  data: T;
  failedSection: DashboardSection | null;
};

async function capture<T>(
  section: DashboardSection,
  task: Promise<T>,
  fallback: T,
): Promise<Captured<T>> {
  try {
    return { data: await task, failedSection: null };
  } catch {
    return { data: fallback, failedSection: section };
  }
}

export async function getDashboardSnapshot(
  timeZone: string,
  now: Date,
): Promise<DashboardLoadResult> {
  const client = await createSupabaseServerClient();
  if (!client) {
    return { status: "configuration-error" };
  }

  const {
    data: { user },
  } = await client.auth.getUser();

  if (!user) {
    return { status: "unauthorized" };
  }

  const { start, end } = getZonedDayBounds(now, timeZone);
  const [
    profile,
    latestPrediction,
    todayPredictions,
    recentAlerts,
    latestWorkout,
  ] =
    await Promise.all([
      capture("profile", getDashboardProfile(client, user.id), null),
      capture("latest-prediction", getLatestPrediction(client, user.id), null),
      capture(
        "today-summary",
        getTodayPredictions(client, user.id, start, end),
        null,
      ),
      capture("recent-alerts", getRecentAlerts(client, user.id), []),
      capture("latest-workout", getLatestWorkout(client, user.id), null),
    ]);

  const failedSections = [
    profile.failedSection,
    latestPrediction.failedSection,
    todayPredictions.failedSection,
    recentAlerts.failedSection,
    latestWorkout.failedSection,
  ].filter((section): section is DashboardSection => section !== null);

  return {
    status: "ready",
    snapshot: {
      profile: profile.data,
      accountEmail: user.email ?? null,
      latestPrediction: latestPrediction.data,
      todaySummary: todayPredictions.data
        ? summarizeToday(todayPredictions.data)
        : null,
      recentAlerts: recentAlerts.data,
      latestWorkout: latestWorkout.data,
      freshness: classifyReadingFreshness(
        latestPrediction.data?.recorded_at,
        now,
      ),
      failedSections,
    },
  };
}
