import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExportPanel } from "@/components/history/export-panel";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:report") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("History CSV controls", () => {
  it("rejects an inverted date range before sending a request", () => {
    render(<ExportPanel defaultFrom="2026-10-01" defaultTo="2026-10-02" timeZone="UTC" />);
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-03" } });
    fireEvent.click(screen.getByRole("button", { name: "Prepare CSV" }));
    expect(screen.getByRole("alert")).toHaveTextContent("valid date range");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("prepares a downloadable CSV for the selected local dates", async () => {
    fetchMock.mockResolvedValue(new Response("record_type\r\nprediction\r\n", {
      headers: { "Content-Type": "text/csv; charset=utf-8" },
    }));
    render(<ExportPanel defaultFrom="2026-10-01" defaultTo="2026-10-02" timeZone="UTC" />);
    fireEvent.click(screen.getByRole("button", { name: "Prepare CSV" }));
    const download = await screen.findByRole("link", { name: "Download CSV" });
    expect(download).toHaveAttribute("href", "blob:report");
    expect(download).toHaveAttribute("download", "stressguard-history-2026-10-01-2026-10-02.csv");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      from: "2026-10-01", to: "2026-10-02", timeZone: "UTC",
    });
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-10-03" } });
    expect(screen.queryByRole("link", { name: "Download CSV" })).not.toBeInTheDocument();
  });
});
