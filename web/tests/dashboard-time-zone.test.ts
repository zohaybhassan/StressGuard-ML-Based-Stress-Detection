import { describe, expect, it } from "vitest";
import {
  getZonedDayBounds,
  normalizeTimeZone,
} from "@/lib/dashboard/time-zone";

describe("getZonedDayBounds", () => {
  it("builds the correct local-day range for Asia/Karachi", () => {
    const bounds = getZonedDayBounds(
      new Date("2026-10-02T12:00:00.000Z"),
      "Asia/Karachi",
    );

    expect(bounds.dateKey).toBe("2026-10-02");
    expect(bounds.start.toISOString()).toBe("2026-10-01T19:00:00.000Z");
    expect(bounds.end.toISOString()).toBe("2026-10-02T19:00:00.000Z");
  });

  it("respects a daylight-saving transition instead of assuming 24 hours", () => {
    const bounds = getZonedDayBounds(
      new Date("2026-11-01T16:00:00.000Z"),
      "America/New_York",
    );

    expect(bounds.start.toISOString()).toBe("2026-11-01T04:00:00.000Z");
    expect(bounds.end.toISOString()).toBe("2026-11-02T05:00:00.000Z");
    expect(bounds.end.getTime() - bounds.start.getTime()).toBe(25 * 60 * 60 * 1000);
  });
});

describe("normalizeTimeZone", () => {
  it("accepts IANA zones and rejects invalid values", () => {
    expect(normalizeTimeZone("Asia/Karachi")).toBe("Asia/Karachi");
    expect(normalizeTimeZone("not/a-zone")).toBeNull();
    expect(normalizeTimeZone(null)).toBeNull();
  });
});
