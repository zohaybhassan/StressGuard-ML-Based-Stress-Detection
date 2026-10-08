import { describe, expect, it } from "vitest";
import { HIGH_STRESS_DAY_THRESHOLD, aggregateTrends, parseTrendRange } from "@/lib/trends/aggregation";
import { getZonedRangeBounds } from "@/lib/dashboard/time-zone";
import type { TrendPredictionInput } from "@/lib/trends/types";

const now = new Date("2026-10-02T12:00:00.000Z");

function prediction(
  recordedAt: string,
  overrides: Partial<TrendPredictionInput> = {},
): TrendPredictionInput {
  return {
    recorded_at: recordedAt,
    label: "not_stressed",
    class_index: 0,
    heart_rate: 76,
    activity_level: 8_400,
    sleep_hours: 7.2,
    out_of_training_range: false,
    ...overrides,
  };
}

describe("trend range selection", () => {
  it("accepts only the supported 7 and 30 day ranges", () => {
    expect(parseTrendRange("30")).toBe(30);
    expect(parseTrendRange("7")).toBe(7);
    expect(parseTrendRange("365")).toBe(7);
    expect(parseTrendRange(undefined)).toBe(7);
  });

  it("creates exactly 7 and 30 local calendar days", () => {
    const seven = getZonedRangeBounds(now, "Asia/Karachi", 7);
    const thirty = getZonedRangeBounds(now, "Asia/Karachi", 30);

    expect(seven.dateKeys).toHaveLength(7);
    expect(seven.dateKeys[0]).toBe("2026-09-26");
    expect(seven.dateKeys.at(-1)).toBe("2026-10-02");
    expect(thirty.dateKeys).toHaveLength(30);
    expect(thirty.dateKeys[0]).toBe("2026-09-03");
    expect(thirty.dateKeys.at(-1)).toBe("2026-10-02");
  });

  it("uses DST-aware range boundaries", () => {
    const bounds = getZonedRangeBounds(
      new Date("2026-11-02T16:00:00.000Z"),
      "America/New_York",
      7,
    );

    expect(bounds.start.toISOString()).toBe("2026-10-27T04:00:00.000Z");
    expect(bounds.end.toISOString()).toBe("2026-11-03T05:00:00.000Z");
    expect(bounds.end.getTime() - bounds.start.getTime()).toBe(169 * 60 * 60 * 1000);
  });
});

describe("aggregateTrends", () => {
  it("uses the Android three-reading threshold for high-stress days", () => {
    expect(HIGH_STRESS_DAY_THRESHOLD).toBe(3);
    const dataset = aggregateTrends(
      [
        prediction("2026-10-01T08:00:00.000Z", { label: "stressed", class_index: 1 }),
        prediction("2026-10-01T09:00:00.000Z", { label: "stressed", class_index: 1 }),
        prediction("2026-10-02T08:00:00.000Z", { label: "stressed", class_index: 1 }),
        prediction("2026-10-02T09:00:00.000Z", { label: "stressed", class_index: 1 }),
        prediction("2026-10-02T10:00:00.000Z", { label: "stressed", class_index: 1 }),
      ],
      7,
      now,
      "UTC",
    );

    expect(dataset.days.find((day) => day.date === "2026-10-01")?.isHighStressDay).toBe(false);
    expect(dataset.days.find((day) => day.date === "2026-10-02")?.isHighStressDay).toBe(true);
    expect(dataset.summary.highStressDays).toBe(1);
  });

  it("preserves missing days as null instead of zero", () => {
    const dataset = aggregateTrends(
      [prediction("2026-10-01T08:00:00.000Z")],
      7,
      now,
      "UTC",
    );

    expect(dataset.days).toHaveLength(7);
    expect(dataset.summary.daysWithData).toBe(1);
    expect(dataset.summary.missingDays).toBe(6);
    expect(dataset.days.find((day) => day.date === "2026-09-30")).toMatchObject({
      readingCount: null,
      stressedProportion: null,
      averageHeartRate: null,
      status: "missing",
    });
  });

  it("calculates stressed-reading proportion and daily averages", () => {
    const dataset = aggregateTrends(
      [
        prediction("2026-10-01T08:00:00.000Z", {
          label: "stressed",
          class_index: 1,
          heart_rate: 80,
          sleep_hours: 6,
          activity_level: 7_000,
        }),
        prediction("2026-10-01T09:00:00.000Z", {
          heart_rate: 90,
          sleep_hours: 8,
          activity_level: 9_000,
        }),
        prediction("2026-10-02T09:00:00.000Z", {
          label: "high_stress",
          class_index: 2,
          heart_rate: 75,
          sleep_hours: 7,
          activity_level: 8_000,
        }),
      ],
      7,
      now,
      "UTC",
    );

    const firstDay = dataset.days.find((day) => day.date === "2026-10-01");
    expect(firstDay).toMatchObject({
      readingCount: 2,
      stressedReadingCount: 1,
      stressedProportion: 0.5,
      averageHeartRate: 85,
      averageSleepHours: 7,
      averageActivityLevel: 8_000,
    });
    expect(dataset.summary.averageDailyStressedProportion).toBe(0.8);
    expect(dataset.summary.averageDailyHeartRate).toBe(80);
  });

  it("keeps sparse sleep and activity values missing", () => {
    const dataset = aggregateTrends(
      [
        prediction("2026-10-01T08:00:00.000Z", {
          sleep_hours: null,
          activity_level: null,
        }),
        prediction("2026-10-02T08:00:00.000Z", {
          sleep_hours: 6.5,
          activity_level: null,
        }),
      ],
      7,
      now,
      "UTC",
    );

    expect(dataset.summary.sleepDays).toBe(1);
    expect(dataset.summary.activityDays).toBe(0);
    expect(dataset.summary.averageDailySleepHours).toBeNull();
    expect(dataset.summary.averageDailyActivityLevel).toBeNull();
    expect(dataset.days.at(-1)?.averageActivityLevel).toBeNull();
  });

  it("aggregates out-of-training-range observations", () => {
    const dataset = aggregateTrends(
      [
        prediction("2026-10-01T08:00:00.000Z", { out_of_training_range: true }),
        prediction("2026-10-01T09:00:00.000Z", { out_of_training_range: true }),
        prediction("2026-10-02T08:00:00.000Z"),
      ],
      7,
      now,
      "UTC",
    );

    expect(dataset.summary.outOfRangeReadings).toBe(2);
    expect(dataset.days.find((day) => day.date === "2026-10-01")?.outOfRangeCount).toBe(2);
  });

  it("groups readings on the correct side of local midnight", () => {
    const dataset = aggregateTrends(
      [
        prediction("2026-10-01T18:59:59.000Z"),
        prediction("2026-10-01T19:00:00.000Z"),
      ],
      7,
      now,
      "Asia/Karachi",
    );

    expect(dataset.days.find((day) => day.date === "2026-10-01")?.readingCount).toBe(1);
    expect(dataset.days.find((day) => day.date === "2026-10-02")?.readingCount).toBe(1);
  });

  it("supports all 30 days without filling absent measurements", () => {
    const dataset = aggregateTrends(
      [prediction("2026-09-03T08:00:00.000Z"), prediction("2026-10-02T08:00:00.000Z")],
      30,
      now,
      "UTC",
    );

    expect(dataset.days).toHaveLength(30);
    expect(dataset.summary.daysWithData).toBe(2);
    expect(dataset.summary.missingDays).toBe(28);
    expect(dataset.days[1].readingCount).toBeNull();
  });
});
