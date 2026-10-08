import {
  CheckCircle,
  ClockCountdown,
  CloudSlash,
} from "@phosphor-icons/react/dist/ssr";
import { classifyReadingFreshness } from "@/lib/dashboard/metrics";
import type { TrendsDataset } from "@/lib/trends/types";
import { formatTrendDateTime } from "./format";
import { RangeSelector } from "./range-selector";
import styles from "./trends.module.css";

const freshnessLabels = {
  fresh: { label: "Fresh data", icon: CheckCircle },
  delayed: { label: "Latest data delayed", icon: ClockCountdown },
  unavailable: { label: "No data yet", icon: CloudSlash },
} as const;

type TrendsHeaderProps = {
  dataset: TrendsDataset;
  now: Date;
};

export function TrendsHeader({ dataset, now }: TrendsHeaderProps) {
  const freshness = classifyReadingFreshness(dataset.summary.latestRecordedAt, now);
  const freshnessItem = freshnessLabels[freshness];
  const FreshnessIcon = freshnessItem.icon;

  return (
    <header className={styles.header}>
      <div>
        <p className={styles.eyebrow}>Health patterns</p>
        <h1>Trends</h1>
        <p className={styles.headerCopy}>
          Understand how synchronized stress readings and their recorded inputs
          change across your local days.
        </p>
      </div>

      <div className={styles.headerActions}>
        <div className={`${styles.freshness} ${styles[`freshness_${freshness}`]}`}>
          <FreshnessIcon size={18} weight="duotone" aria-hidden="true" />
          <span>
            <strong>{freshnessItem.label}</strong>
            <small>
              Latest reading: {formatTrendDateTime(dataset.summary.latestRecordedAt, dataset.timeZone)}
            </small>
          </span>
        </div>
        <RangeSelector range={dataset.range} />
      </div>
    </header>
  );
}
