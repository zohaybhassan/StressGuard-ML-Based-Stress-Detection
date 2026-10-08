import { REPORT_CSV_COLUMNS, type ReportCsvRow } from "./types";

function cell(value: ReportCsvRow[keyof ReportCsvRow]) {
  if (value === null) return '""';
  let text = String(value);
  // Spreadsheet applications may evaluate cells beginning with a formula prefix.
  if (/^\s*[=+\-@]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

export function renderReportCsv(rows: ReportCsvRow[]) {
  return [
    REPORT_CSV_COLUMNS.join(","),
    ...rows.map((row) => REPORT_CSV_COLUMNS.map((column) => cell(row[column])).join(",")),
  ].join("\r\n") + "\r\n";
}
