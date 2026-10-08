import { describe, expect, it } from "vitest";
import { getZonedDateRangeBounds } from "@/lib/dashboard/time-zone";
import { normalizeReportRows } from "@/lib/reports/normalization";
import { REPORT_MAX_ROWS } from "@/lib/reports/types";
import { reportRequestSchema, reportErrorResponse } from "@/lib/reports/validation";
import { enforceReportRowLimit, ReportTooLargeError } from "@/lib/reports/limits";

describe("report validation", () => {
  it("accepts 90 days and rejects 91 days", () => {
    expect(reportRequestSchema.safeParse({ from: "2026-01-01", to: "2026-03-31", timeZone: "Asia/Karachi" }).success).toBe(true);
    expect(reportRequestSchema.safeParse({ from: "2026-01-01", to: "2026-04-01", timeZone: "Asia/Karachi" }).success).toBe(false);
  });

  it("maps only friendly public errors", () => {
    expect(reportErrorResponse("unauthorized")).toMatch(/Sign in/);
    expect(reportErrorResponse("unknown_internal_error")).not.toContain("unknown_internal_error");
  });

  it("enforces the aggregate row cap", () => {
    expect(enforceReportRowLimit([100, 200, 300])).toBe(600);
    expect(() => enforceReportRowLimit([REPORT_MAX_ROWS, 1])).toThrow(ReportTooLargeError);
  });
});
describe("report normalization", () => {
  it("preserves missing fields as blank-ready nulls and sorts chronologically", () => {
    const rows = normalizeReportRows({
      predictions: [{ recorded_at: "2026-10-02T08:00:00.000Z", label: "stressed", confidence: .82, heart_rate: 92, sleep_hours: 6.5, activity_level: 2, daily_steps: 2100, model_version: "v1", out_of_training_range: false }],
      alerts: [{ fired_at: "2026-10-02T07:00:00.000Z", dismissed: false, model_version: "v1", reason: "window threshold", high_count_in_window: 3, window_size: 5 }],
      feedback: [], workouts: [],
    }, "Asia/Karachi", new Date("2026-10-02T12:00:00.000Z"));
    expect(rows.map((row) => row.record_type)).toEqual(["alert", "prediction"]);
    expect(rows[0].heart_rate_bpm).toBeNull();
    expect(rows[1].local_date).toBe("2026-10-02");
  });

  it("converts inclusive local dates across DST to exclusive UTC bounds", () => {
    const bounds = getZonedDateRangeBounds("2026-11-01", "2026-11-01", "America/New_York");
    expect(bounds.start?.toISOString()).toBe("2026-11-01T04:00:00.000Z");
    expect(bounds.end?.toISOString()).toBe("2026-11-02T05:00:00.000Z");
  });
});
