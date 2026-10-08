import { z } from "zod";
import { HISTORY_MAX_PAGE, type HistoryFilters } from "./types";

const dateKey = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/)
  .refine((value) => {
    const [year, month, day] = value.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day));
    return (
      date.getUTCFullYear() === year &&
      date.getUTCMonth() + 1 === month &&
      date.getUTCDate() === day
    );
  });

const historySearchSchema = z.object({
  tab: z.enum(["predictions", "alerts", "workouts"]).default("predictions"),
  page: z.coerce.number().int().min(1).max(HISTORY_MAX_PAGE).default(1),
  from: z.union([dateKey, z.literal("")]).optional(),
  to: z.union([dateKey, z.literal("")]).optional(),
  label: z.enum(["all", "stressed", "not_stressed", "high_stress"]).default("all"),
  inputRange: z.enum(["all", "warning", "within"]).default("all"),
  records: z.enum(["all", "alerts", "feedback"]).default("all"),
  dismissed: z.enum(["all", "yes", "no"]).default("all"),
  source: z.enum(["all", "high_stress_alert", "periodic_check_in"]).default("all"),
  status: z.enum(["all", "active", "paused", "completed"]).default("all"),
  detail: z
    .string()
    .max(80)
    .regex(/^(prediction|alert|feedback|workout):[A-Za-z0-9-]+$/)
    .optional(),
});

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

export type HistorySearchParams = Record<string, string | string[] | undefined>;

export type HistoryParseResult =
  | { success: true; filters: HistoryFilters }
  | { success: false; message: string };

export function parseHistoryFilters(input: HistorySearchParams): HistoryParseResult {
  const parsed = historySearchSchema.safeParse({
    tab: first(input.tab),
    page: first(input.page),
    from: first(input.from),
    to: first(input.to),
    label: first(input.label),
    inputRange: first(input.inputRange),
    records: first(input.records),
    dismissed: first(input.dismissed),
    source: first(input.source),
    status: first(input.status),
    detail: first(input.detail),
  });

  if (!parsed.success) {
    return { success: false, message: "Some history filters are invalid." };
  }

  const from = parsed.data.from || null;
  const to = parsed.data.to || null;
  if (from && to && from > to) {
    return { success: false, message: "The start date must not be after the end date." };
  }

  return {
    success: true,
    filters: {
      tab: parsed.data.tab,
      page: parsed.data.page,
      from,
      to,
      predictionLabel: parsed.data.label,
      inputRange: parsed.data.inputRange,
      alertRecords: parsed.data.records,
      dismissed: parsed.data.dismissed,
      feedbackSource: parsed.data.source,
      workoutStatus: parsed.data.status,
      detail: parsed.data.detail ?? null,
    },
  };
}

export function historyQueryString(
  filters: HistoryFilters,
  overrides: Partial<Record<"tab" | "page" | "detail", string | number | null>> = {},
) {
  const values = {
    tab: overrides.tab ?? filters.tab,
    page: overrides.page ?? filters.page,
    from: filters.from,
    to: filters.to,
    label: filters.predictionLabel,
    inputRange: filters.inputRange,
    records: filters.alertRecords,
    dismissed: filters.dismissed,
    source: filters.feedbackSource,
    status: filters.workoutStatus,
    detail: overrides.detail === undefined ? filters.detail : overrides.detail,
  };
  const params = new URLSearchParams();

  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== "" && value !== "all") {
      params.set(key, String(value));
    }
  });

  return params.toString();
}
