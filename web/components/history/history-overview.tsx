import { ClockCounterClockwise } from "@phosphor-icons/react/dist/ssr";
import type { HistoryFilters, HistoryItem, HistoryPageData } from "@/lib/history/types";
import { ExportPanel } from "./export-panel";
import { HistoryDetailDialog } from "./history-detail-dialog";
import { HistoryFiltersForm } from "./history-filters";
import { HistoryPagination } from "./history-pagination";
import { HistoryTable } from "./history-table";
import { HistoryTabs } from "./history-tabs";
import styles from "./history.module.css";

export function HistoryOverview({ data, filters, timeZone, detail, exportRange }: {
  data: HistoryPageData;
  filters: HistoryFilters;
  timeZone: string;
  detail: HistoryItem | null;
  exportRange: { from: string; to: string };
}) {
  return (
    <div className={styles.history}>
      <header className={styles.header}>
        <div><p className={styles.eyebrow}>Synchronized records</p><h1>History</h1>
          <p>Review the readings, alerts, feedback, and workout sessions saved by StressGuard.</p></div>
        <div className={styles.timeZoneBadge}>Dates shown in <strong>{timeZone}</strong></div>
      </header>
      <HistoryTabs filters={filters} />
      <HistoryFiltersForm filters={filters} />
      <section className={styles.recordsCard} aria-labelledby="history-records-heading">
        <div className={styles.recordsHeader}>
          <div><h2 id="history-records-heading">{data.tab === "alerts" ? "Alerts and feedback" : data.tab}</h2>
            <p>Newest records appear first. Measurements remain distinct when data is unavailable.</p></div>
          <strong>{data.total.toLocaleString("en-US")} records</strong>
        </div>
        {data.items.length ? <><HistoryTable items={data.items} tab={data.tab} timeZone={timeZone} />
          <HistoryPagination data={data} filters={filters} /></> : (
          <div className={styles.emptyState} role="status"><span aria-hidden="true"><ClockCounterClockwise size={30} weight="duotone" /></span>
            <h3>No records match these filters</h3><p>Try a wider date range, or let the Android app synchronize new StressGuard data.</p></div>
        )}
      </section>
      <ExportPanel defaultFrom={exportRange.from} defaultTo={exportRange.to} timeZone={timeZone} />
      <HistoryDetailDialog item={detail} timeZone={timeZone} />
    </div>
  );
}
