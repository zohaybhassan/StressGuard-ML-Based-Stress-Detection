import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { DashboardOverview } from "@/components/dashboard/dashboard-overview";
import type { DashboardSnapshot } from "@/lib/dashboard/types";

afterEach(cleanup);

function emptySnapshot(overrides: Partial<DashboardSnapshot> = {}): DashboardSnapshot {
  return {
    profile: null,
    accountEmail: "test@example.com",
    latestPrediction: null,
    todaySummary: {
      readingCount: 0,
      stressedCount: 0,
      stressedProportion: null,
      latestRecordedAt: null,
    },
    recentAlerts: [],
    latestWorkout: null,
    freshness: "unavailable",
    latestCloudRecordAt: null,
    failedSections: [],
    ...overrides,
  };
}

describe("DashboardOverview", () => {
  it("renders a safe fallback greeting and explicit empty states", () => {
    render(
      <DashboardOverview
        snapshot={emptySnapshot()}
        now={new Date("2026-10-02T12:00:00.000Z")}
        timeZone="UTC"
      />,
    );

    expect(screen.getByRole("heading", { name: "Good afternoon" })).toBeInTheDocument();
    expect(screen.getByText("No stress readings yet")).toBeInTheDocument();
    expect(screen.getByText("No readings today")).toBeInTheDocument();
    expect(screen.getByText("No alerts recorded")).toBeInTheDocument();
    expect(screen.getByText("No workouts synchronized")).toBeInTheDocument();
  });

  it("shows section-level failures without replacing healthy sections", () => {
    render(
      <DashboardOverview
        snapshot={emptySnapshot({
          todaySummary: null,
          failedSections: ["today-summary", "recent-alerts"],
        })}
        now={new Date("2026-10-02T12:00:00.000Z")}
        timeZone="UTC"
      />,
    );

    expect(screen.getByText("Summary unavailable")).toBeInTheDocument();
    expect(screen.getByText("Your alert history could not be retrieved.")).toBeInTheDocument();
    expect(screen.getByText("No workouts synchronized")).toBeInTheDocument();
  });
});
