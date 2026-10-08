import { ChartLineUp } from "@phosphor-icons/react/dist/ssr";
import type { TrendsDataset } from "@/lib/trends/types";
import { DataQuality } from "./data-quality";
import { TrendCharts } from "./trend-charts";
import { RangeSelector } from "./range-selector";
import { TrendsHeader } from "./trends-header";
import { TrendSummary } from "./trend-summary";
import styles from "./trends.module.css";

type TrendsOverviewProps = {
  dataset: TrendsDataset;
  now: Date;
};

export function TrendsOverview({ dataset, now }: TrendsOverviewProps) {
  if (dataset.summary.totalReadings === 0) {
    return (
      <div className={styles.trends}>
        <header className={styles.header}>
          <div>
            <p className={styles.eyebrow}>Health patterns</p>
            <h1>Trends</h1>
            <p className={styles.headerCopy}>
              Understand how synchronized stress readings and their recorded inputs
              change across your local days.
            </p>
          </div>
          <RangeSelector range={dataset.range} />
        </header>

        <section className={styles.emptyState} role="status">
          <span aria-hidden="true">
            <ChartLineUp size={34} weight="duotone" />
          </span>
          <h2>No predictions in this range</h2>
          <p>
            Complete stress readings in the Android app and allow them to synchronize.
            Missing days are not treated as zero-stress days.
          </p>
        </section>

        <DataQuality dataset={dataset} />
      </div>
    );
  }

  return (
    <div className={styles.trends}>
      <TrendsHeader dataset={dataset} now={now} />
      <TrendSummary dataset={dataset} />
      <TrendCharts dataset={dataset} />
      <DataQuality dataset={dataset} />
    </div>
  );
}
