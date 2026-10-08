import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

const mocks = vi.hoisted(() => ({
  getUser: vi.fn(),
  createClient: vi.fn(),
  getReportData: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/lib/supabase/server", () => ({ createSupabaseServerClient: mocks.createClient }));
vi.mock("@/lib/reports/repository", () => ({
  getReportData: mocks.getReportData,
}));
vi.mock("@/lib/aws/report-invoker", () => ({
  getReportLambdaConfig: vi.fn(() => null),
  invokeReportLambda: vi.fn(),
}));

import { POST } from "@/app/api/reports/route";

function request(body: unknown) {
  return new NextRequest("https://stressguard.test/api/reports", {
    method: "POST",
    headers: { origin: "https://stressguard.test", host: "stressguard.test", "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

describe("report API boundary", () => {
  beforeEach(() => {
    mocks.getUser.mockReset();
    mocks.getReportData.mockReset();
    mocks.createClient.mockResolvedValue({ auth: { getUser: mocks.getUser } });
  });

  it("rejects a signed-out request before report configuration is inspected", async () => {
    mocks.getUser.mockResolvedValue({ data: { user: null } });
    const response = await POST(request({ from: "2026-10-01", to: "2026-10-02", timeZone: "UTC" }));
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ code: "unauthorized" });
  });

  it("rejects malformed input for an authenticated session", async () => {
    mocks.getUser.mockResolvedValue({ data: { user: { id: "private-user" } } });
    const response = await POST(request({ userId: "another-user", from: "bad-date", to: "2026-10-02", timeZone: "UTC" }));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ code: "invalid_request" });
  });

  it("rejects a cross-origin browser request", async () => {
    const crossOrigin = new NextRequest("https://stressguard.test/api/reports", {
      method: "POST",
      headers: { origin: "https://evil.example", host: "stressguard.test" },
      body: "{}",
    });
    const response = await POST(crossOrigin);
    expect(response.status).toBe(403);
    expect(mocks.getUser).not.toHaveBeenCalled();
  });

  it("prepares a private CSV directly when the optional AWS function is absent", async () => {
    mocks.getUser.mockResolvedValue({ data: { user: { id: "private-user" } } });
    mocks.getReportData.mockResolvedValue({
      predictions: [{
        recorded_at: "2026-10-02T08:00:00.000Z", label: "stressed", confidence: .82,
        heart_rate: 92, sleep_hours: 6.5, activity_level: 2000, daily_steps: 2100,
        model_version: "v1", out_of_training_range: false,
      }],
      alerts: [], feedback: [], workouts: [],
    });

    const response = await POST(request({ from: "2026-10-01", to: "2026-10-02", timeZone: "UTC" }));
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/csv");
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(response.headers.get("content-disposition")).toContain("stressguard-history-2026-10-01-2026-10-02.csv");
    expect(await response.text()).toContain('"prediction","2026-10-02T08:00:00.000Z","2026-10-02"');
    expect(mocks.getReportData).toHaveBeenCalledOnce();
  });
});
