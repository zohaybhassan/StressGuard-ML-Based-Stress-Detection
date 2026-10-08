import "server-only";

import { createSupabaseServerClient } from "@/lib/supabase/server";
import { getZonedRangeBounds } from "@/lib/dashboard/time-zone";
import { aggregateTrends } from "./aggregation";
import type {
  TrendPredictionInput,
  TrendRange,
  TrendsLoadResult,
} from "./types";

const QUERY_PAGE_SIZE = 1_000;

type TrendsClient = NonNullable<
  Awaited<ReturnType<typeof createSupabaseServerClient>>
>;

export async function getTrendPredictionRows(
  client: TrendsClient,
  userId: string,
  start: Date,
  end: Date,
) {
  const rows: TrendPredictionInput[] = [];

  for (let offset = 0; ; offset += QUERY_PAGE_SIZE) {
    const { data, error } = await client
      .from("stress_predictions")
      .select(
        "recorded_at,label,class_index,heart_rate,activity_level,sleep_hours,out_of_training_range",
      )
      .eq("user_id", userId)
      .gte("recorded_at", start.toISOString())
      .lt("recorded_at", end.toISOString())
      .order("recorded_at", { ascending: true })
      .range(offset, offset + QUERY_PAGE_SIZE - 1);

    if (error) {
      throw new Error("Trends query failed");
    }

    const page = data as TrendPredictionInput[];
    rows.push(...page);
    if (page.length < QUERY_PAGE_SIZE) {
      break;
    }
  }

  return rows;
}

export async function getTrendsDataset(
  range: TrendRange,
  timeZone: string,
  now: Date,
): Promise<TrendsLoadResult> {
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

  const bounds = getZonedRangeBounds(now, timeZone, range);

  try {
    const rows = await getTrendPredictionRows(
      client,
      user.id,
      bounds.start,
      bounds.end,
    );
    return {
      status: "ready",
      dataset: aggregateTrends(rows, range, now, timeZone),
    };
  } catch {
    return { status: "query-error" };
  }
}
