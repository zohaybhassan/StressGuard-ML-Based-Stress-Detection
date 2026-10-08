import { ChartDonut, Clock, WarningCircle } from "@phosphor-icons/react/dist/ssr";
import type { DashboardSnapshot } from "@/lib/dashboard/types";
import { formatDateTime } from "./format";
import styles from "./dashboard.module.css";

type TodaySummaryProps = {
  snapshot: DashboardSnapshot;
  timeZone: string;
};

export function TodaySummary({ snapshot, timeZone }: TodaySummaryProps) {
  const failed = snapshot.failedSections.includes("today-summary");
  const summary = snapshot.todaySummary;
  const percentage = summary?.stressedProportion === null
    ? null
    : Math.round((summary?.stressedProportion ?? 0) * 100);

  return (
    <section className={styles.summaryCard} aria-labelledby="today-summary-heading">
      <div className={styles.cardHeadingRow}>
        <div>
          <p className={styles.cardEyebrow}>Your local day</p>
          <h2 id="today-summary-heading" className={styles.cardTitle}>
            Today&apos;s summary
          </h2>
        </div>
        <span className={styles.summaryIcon} aria-hidden="true">
          <ChartDonut size={26} weight="duotone" />
        </span>
      </div>

      {failed || !summary ? (
        <div className={styles.inlineError} role="status">
          <WarningCircle size={22} weight="duotone" aria-hidden="true" />
          <div>
            <strong>Summary unavailable</strong>
            <p>Today&apos;s readings could not be retrieved.</p>
          </div>
        </div>
      ) : summary.readingCount === 0 ? (
        <div className={styles.emptyCompact}>
          <strong>No readings today</strong>
          <p>New mobile readings will appear here after synchronization.</p>
        </div>
      ) : (
        <>
          <div className={styles.summaryNumbers}>
            <div>
              <strong>{summary.readingCount}</strong>
              <span>Total readings</span>
            </div>
            <div>
              <strong>{summary.stressedCount}</strong>
              <span>Stressed readings</span>
            </div>
            <div>
              <strong>{percentage}%</strong>
              <span>Stressed proportion</span>
            </div>
          </div>
          <div className={styles.latestTime}>
            <Clock size={17} weight="duotone" aria-hidden="true" />
            Latest today: {formatDateTime(summary.latestRecordedAt, timeZone)}
          </div>
        </>
      )}

      <p className={styles.timeZoneNote}>Day boundaries use {timeZone}.</p>
    </section>
  );
}
