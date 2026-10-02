import {
  CheckCircle,
  Info,
  WarningCircle,
} from "@phosphor-icons/react/dist/ssr";
import type { TrendsDataset } from "@/lib/trends/types";
import styles from "./trends.module.css";

export function DataQuality({ dataset }: { dataset: TrendsDataset }) {
  const { days, range, summary, timeZone } = dataset;
  const missingLabels = days
    .filter((day) => day.status === "missing")
    .map((day) => day.shortLabel);
  const visibleMissing = missingLabels.slice(0, 5);

  return (
    <section className={styles.qualityCard} aria-labelledby="data-quality-heading">
      <div className={styles.qualityHeader}>
        <div>
          <p className={styles.cardEyebrow}>Coverage and interpretation</p>
          <h2 id="data-quality-heading">Data quality context</h2>
        </div>
        <Info size={24} weight="duotone" aria-hidden="true" />
      </div>

      <div className={styles.qualityGrid}>
        <div className={styles.qualityItem}>
          <CheckCircle size={20} weight="duotone" aria-hidden="true" />
          <div>
            <strong>{summary.daysWithData} of {range} local days observed</strong>
            <p>
              Days are grouped in {timeZone}. Today is marked in progress when it
              contains readings.
            </p>
          </div>
        </div>

        <div className={styles.qualityItem}>
          <WarningCircle size={20} weight="duotone" aria-hidden="true" />
          <div>
            <strong>{summary.outOfRangeReadings} input-range warnings</strong>
            <p>
              These readings remain visible, but at least one model input was outside
              its training range.
            </p>
          </div>
        </div>
      </div>

      {missingLabels.length > 0 ? (
        <div className={styles.missingDays}>
          <strong>Missing days</strong>
          <p>
            {visibleMissing.join(", ")}
            {missingLabels.length > visibleMissing.length
              ? ` and ${missingLabels.length - visibleMissing.length} more`
              : ""}
          </p>
        </div>
      ) : null}

      <div className={styles.schemaNotes}>
        <p>
          <strong>Sleep:</strong> values are synchronized inputs stored with stress
          predictions, not complete sleep sessions.
        </p>
        <p>
          <strong>Activity:</strong> the chart uses the model&apos;s full-day activity
          input for Android parity. Step values are prediction-time cumulative
          snapshots, not a standalone step-history stream.
        </p>
        {summary.modelVersions.length > 0 ? (
          <p>
            <strong>Models observed:</strong> {summary.modelVersions.join(", ")}
          </p>
        ) : null}
      </div>
    </section>
  );
}
