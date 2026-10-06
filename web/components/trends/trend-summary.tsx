import {
  CalendarCheck,
  Heartbeat,
  Pulse,
  TrendUp,
} from "@phosphor-icons/react/dist/ssr";
import type { TrendsDataset } from "@/lib/trends/types";
import { formatMetric, formatPercent } from "./format";
import styles from "./trends.module.css";

export function TrendSummary({ dataset }: { dataset: TrendsDataset }) {
  const { summary, range } = dataset;
  const items = [
    {
      label: "Average daily stress share",
      value: formatPercent(summary.averageDailyStressedProportion),
      detail:
        summary.daysWithData < 2
          ? "Needs at least two observed days"
          : `Across ${summary.daysWithData} observed days`,
      icon: TrendUp,
      tone: "stress",
    },
    {
      label: "Total readings",
      value: summary.totalReadings.toLocaleString("en-US"),
      detail: `${summary.totalStressedReadings.toLocaleString("en-US")} classified as stressed`,
      icon: Pulse,
      tone: "readings",
    },
    {
      label: "Average daily heart rate",
      value: formatMetric(summary.averageDailyHeartRate, "bpm"),
      detail:
        summary.heartRateDays < 2
          ? "Needs at least two observed days"
          : `Based on ${summary.heartRateDays} days`,
      icon: Heartbeat,
      tone: "heart",
    },
    {
      label: "Data coverage",
      value: `${summary.daysWithData} of ${range}`,
      detail: `${summary.missingDays} missing ${summary.missingDays === 1 ? "day" : "days"}`,
      icon: CalendarCheck,
      tone: "coverage",
    },
  ] as const;

  return (
    <section className={styles.summaryGrid} aria-label={`${range}-day trend summary`}>
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <article key={item.label} className={`${styles.summaryCard} ${styles[`summary_${item.tone}`]}`}>
            <span className={styles.summaryIcon} aria-hidden="true">
              <Icon size={21} weight="duotone" />
            </span>
            <p>{item.label}</p>
            <strong>{item.value}</strong>
            <small>{item.detail}</small>
          </article>
        );
      })}
    </section>
  );
}
