import { describe, expect, it } from "vitest";
import { parseHistoryFilters, historyQueryString } from "@/lib/history/validation";

describe("History filters", () => {
  it("uses safe defaults", () => {
    const result = parseHistoryFilters({});
    expect(result.success).toBe(true);
    if (result.success) expect(result.filters).toMatchObject({ tab: "predictions", page: 1 });
  });

  it("parses meaningful alert filters and preserves them in pagination URLs", () => {
    const result = parseHistoryFilters({ tab: "alerts", from: "2026-09-01", to: "2026-09-30", records: "feedback", source: "periodic_check_in" });
    expect(result.success).toBe(true);
    if (!result.success) return;
    expect(historyQueryString(result.filters, { page: 2 })).toContain("page=2");
    expect(historyQueryString(result.filters, { page: 2 })).toContain("records=feedback");
  });

  it("rejects invalid calendar order and oversized pages", () => {
    expect(parseHistoryFilters({ from: "2026-10-02", to: "2026-10-01" }).success).toBe(false);
    expect(parseHistoryFilters({ page: "101" }).success).toBe(false);
  });
});
