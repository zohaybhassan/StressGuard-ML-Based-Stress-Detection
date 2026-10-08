import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { TrendsOverview } from "@/components/trends/trends-overview";
import { aggregateTrends } from "@/lib/trends/aggregation";
import type { TrendPredictionInput } from "@/lib/trends/types";

afterEach(cleanup);

const now = new Date("2026-10-02T12:00:00.000Z");

function prediction(recordedAt: string): TrendPredictionInput {
  return {
    recorded_at: recordedAt,
    label: "not_stressed",
    class_index: 0,
    heart_rate: 78,
    activity_level: 8_200,
    sleep_hours: 7.1,
    out_of_training_range: false,
  };
}

describe("TrendsOverview", () => {
  it("renders the empty state and both supported controls without empty charts", () => {
    const dataset = aggregateTrends([], 7, now, "UTC");
    render(<TrendsOverview dataset={dataset} now={now} />);

    expect(screen.getByRole("heading", { name: "No predictions in this range" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "7 Days" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "30 Days" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Trend charts" })).not.toBeInTheDocument();
  });

  it("explains that one day is insufficient for a trend", () => {
    const dataset = aggregateTrends(
      [prediction("2026-10-02T08:00:00.000Z")],
      7,
      now,
      "UTC",
    );
    render(<TrendsOverview dataset={dataset} now={now} />);

    expect(screen.getByRole("region", { name: "Trend charts" })).toBeInTheDocument();
    expect(screen.getAllByText("One observed day so far")).toHaveLength(4);
    expect(screen.getByText("1 of 7 local days observed")).toBeInTheDocument();
  });

  it("shows the four original chart subjects when two days have readings", () => {
    const dataset = aggregateTrends(
      [prediction("2026-10-01T08:00:00.000Z"), prediction("2026-10-02T08:00:00.000Z")],
      7,
      now,
      "UTC",
    );
    render(<TrendsOverview dataset={dataset} now={now} />);

    expect(screen.getByRole("region", { name: "Trend charts" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Stress overview" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Heart rate trend" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Sleep trend" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Activity trend" })).toBeInTheDocument();
    expect(screen.queryByText(/input-range warnings/i)).not.toBeInTheDocument();
  });
});
