import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FeatureGrid } from "@/components/landing/feature-grid";
import { FinalCta } from "@/components/landing/final-cta";
import { sampleTrends, type SamplePeriod } from "@/components/landing/sample-trends";

beforeEach(() => {
  vi.stubGlobal("IntersectionObserver", class {
    observe() {}
    disconnect() {}
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("public landing content", () => {
  it("presents feature cards without misleading learn-more links", () => {
    render(<FeatureGrid />);
    expect(screen.getAllByRole("article")).toHaveLength(6);
    expect(screen.queryByRole("link", { name: /learn more/i })).not.toBeInTheDocument();
  });

  it("explains what StressGuard does without a medical claim", () => {
    render(<FinalCta />);
    expect(screen.getByRole("heading", { name: "Wellness, made clearer." })).toBeInTheDocument();
    expect(screen.getByText(/does not diagnose medical conditions/i)).toBeInTheDocument();
  });

  it("has distinct sample readings and chart shapes for each period", () => {
    const periods: SamplePeriod[] = ["Day", "Week", "Month", "Year"];
    for (const metric of ["stress", "heart", "steps", "sleep"] as const) {
      expect(new Set(periods.map((period) => sampleTrends[period].metrics[metric].value)).size).toBe(4);
      expect(new Set(periods.map((period) => sampleTrends[period].metrics[metric].values.join(","))).size).toBe(4);
    }
  });
});
