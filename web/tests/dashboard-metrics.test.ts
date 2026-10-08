import { describe, expect, it } from "vitest";
import {
  FRESHNESS_THRESHOLD_MINUTES,
  classifyReadingFreshness,
  getWorkoutAverageHeartRate,
  getWorkoutDurationMs,
  getWorkoutStepCount,
  summarizeToday,
} from "@/lib/dashboard/metrics";
import type { Database } from "@/types/database";

type Workout = Database["public"]["Tables"]["workout_sessions"]["Row"];

const now = new Date("2026-10-02T12:00:00.000Z");

function workout(overrides: Partial<Workout> = {}): Workout {
  return {
    id: 1,
    user_id: "user-1",
    started_at: "2026-10-02T10:00:00.000Z",
    planned_end_at: "2026-10-02T11:00:00.000Z",
    ended_at: "2026-10-02T10:40:00.000Z",
    status: "completed",
    total_paused_ms: 300_000,
    first_steps: 1_000,
    last_steps: 2_250,
    min_heart_rate: 70,
    max_heart_rate: 142,
    heart_rate_sum: 1_000,
    heart_rate_samples: 10,
    updated_at: "2026-10-02T10:40:00.000Z",
    created_at: "2026-10-02T10:41:00.000Z",
    ...overrides,
  };
}

describe("classifyReadingFreshness", () => {
  it("uses a ten-minute freshness window", () => {
    expect(FRESHNESS_THRESHOLD_MINUTES).toBe(10);
  });

  it("treats a reading at the threshold as fresh", () => {
    const recordedAt = new Date(
      now.getTime() - FRESHNESS_THRESHOLD_MINUTES * 60_000,
    ).toISOString();

    expect(classifyReadingFreshness(recordedAt, now)).toBe("fresh");
  });

  it("marks older readings as delayed", () => {
    const recordedAt = new Date(
      now.getTime() - FRESHNESS_THRESHOLD_MINUTES * 60_000 - 1,
    ).toISOString();

    expect(classifyReadingFreshness(recordedAt, now)).toBe("delayed");
  });

  it("marks missing and invalid timestamps as unavailable", () => {
    expect(classifyReadingFreshness(null, now)).toBe("unavailable");
    expect(classifyReadingFreshness("not-a-date", now)).toBe("unavailable");
  });
});

describe("summarizeToday", () => {
  it("counts readings, stressed labels, proportion, and newest timestamp", () => {
    const summary = summarizeToday([
      {
        recorded_at: "2026-10-02T09:00:00.000Z",
        label: "not_stressed",
        class_index: 0,
      },
      {
        recorded_at: "2026-10-02T11:00:00.000Z",
        label: "stressed",
        class_index: 1,
      },
      {
        recorded_at: "2026-10-02T10:00:00.000Z",
        label: "high stress",
        class_index: 2,
      },
    ]);

    expect(summary).toEqual({
      readingCount: 3,
      stressedCount: 2,
      stressedProportion: 2 / 3,
      latestRecordedAt: "2026-10-02T11:00:00.000Z",
    });
  });

  it("does not turn missing readings into zero-valued health data", () => {
    expect(summarizeToday([])).toEqual({
      readingCount: 0,
      stressedCount: 0,
      stressedProportion: null,
      latestRecordedAt: null,
    });
  });
});

describe("workout metrics", () => {
  it("subtracts paused time from completed workout duration", () => {
    expect(getWorkoutDurationMs(workout(), now)).toBe(35 * 60_000);
  });

  it("preserves unknown optional step and heart-rate values", () => {
    const missingMetrics = workout({
      first_steps: null,
      last_steps: null,
      heart_rate_samples: 0,
      heart_rate_sum: 0,
    });

    expect(getWorkoutStepCount(missingMetrics)).toBeNull();
    expect(getWorkoutAverageHeartRate(missingMetrics)).toBeNull();
  });

  it("calculates valid step and average heart-rate values", () => {
    const completed = workout();

    expect(getWorkoutStepCount(completed)).toBe(1_250);
    expect(getWorkoutAverageHeartRate(completed)).toBe(100);
  });
});
