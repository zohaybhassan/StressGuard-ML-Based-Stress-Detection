import type { Database } from "@/types/database";
import type { ReadingFreshness, TodaySummary } from "./metrics";

export type ProfileSummary = Pick<
  Database["public"]["Tables"]["profiles"]["Row"],
  "display_name" | "email" | "avatar_url"
>;

export type LatestPrediction = Database["public"]["Tables"]["stress_predictions"]["Row"];
export type RecentAlert = Database["public"]["Tables"]["alert_events"]["Row"];
export type LatestWorkout = Database["public"]["Tables"]["workout_sessions"]["Row"];

export type DashboardSection =
  | "profile"
  | "latest-prediction"
  | "today-summary"
  | "recent-alerts"
  | "latest-workout";

export type DashboardSnapshot = {
  profile: ProfileSummary | null;
  accountEmail: string | null;
  latestPrediction: LatestPrediction | null;
  todaySummary: TodaySummary | null;
  recentAlerts: RecentAlert[];
  latestWorkout: LatestWorkout | null;
  freshness: ReadingFreshness;
  failedSections: DashboardSection[];
};

export type DashboardLoadResult =
  | { status: "ready"; snapshot: DashboardSnapshot }
  | { status: "unauthorized" }
  | { status: "configuration-error" };
