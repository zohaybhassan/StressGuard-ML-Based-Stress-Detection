import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { HistoryDetailDialog } from "@/components/history/history-detail-dialog";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/history",
  useSearchParams: () => new URLSearchParams("tab=predictions&detail=prediction%3A1"),
}));

describe("History detail dialog", () => {
  it("shows an accessible prediction detail and removes its URL state on close", () => {
    render(<HistoryDetailDialog timeZone="UTC" item={{
      kind: "prediction", id: 1, occurredAt: "2026-10-02T08:00:00.000Z",
      label: "stressed", classIndex: 1, confidence: .82, heartRate: 92,
      sleepHours: 6.5, activityLevel: 2, dailySteps: 2100,
      modelVersion: "v1", outOfTrainingRange: false,
    }} />);
    expect(screen.getByRole("heading", { name: "Prediction" })).toBeVisible();
    expect(screen.getByText("82%")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Close history detail" }));
    expect(router.replace).toHaveBeenCalledWith("/history?tab=predictions", { scroll: false });
  });
});
