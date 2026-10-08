import { REPORT_MAX_ROWS } from "./types";

export class ReportTooLargeError extends Error {
  constructor() {
    super("Report row limit exceeded");
    this.name = "ReportTooLargeError";
  }
}

export function enforceReportRowLimit(counts: number[]) {
  const total = counts.reduce((sum, count) => sum + count, 0);
  if (total > REPORT_MAX_ROWS) throw new ReportTooLargeError();
  return total;
}
