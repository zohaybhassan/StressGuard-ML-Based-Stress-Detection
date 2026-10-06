import {
  Footprints,
  Heartbeat,
  MoonStars,
  Pulse,
  WarningCircle,
} from "@phosphor-icons/react/dist/ssr";
import type { DashboardSnapshot } from "@/lib/dashboard/types";
import { formatDateTime, formatStressLabel } from "./format";
import styles from "./dashboard.module.css";

type StressOverviewProps = {
  snapshot: DashboardSnapshot;
  timeZone: string;
};

function SectionError({ message }: { message: string }) {
  return (
    <div className={styles.inlineError} role="status">
      <WarningCircle size={22} weight="duotone" aria-hidden="true" />
      <div>
        <strong>Unable to load this section</strong>
        <p>{message}</p>
      </div>
    </div>
  );
}

export function StressOverview({ snapshot, timeZone }: StressOverviewProps) {
  const predictionFailed = snapshot.failedSections.includes("latest-prediction");
  const prediction = snapshot.latestPrediction;

  return (
    <section aria-labelledby="latest-stress-heading" className={styles.overviewGrid}>
      <article className={styles.stressCard}>
        <div className={styles.cardHeadingRow}>
          <div>
            <p className={styles.cardEyebrow}>Latest synchronized result</p>
            <h2 id="latest-stress-heading" className={styles.cardTitle}>
              Stress overview
            </h2>
          </div>
          <span className={styles.heroIcon} aria-hidden="true">
            <Pulse size={27} weight="duotone" />
          </span>
        </div>

        {predictionFailed ? (
          <SectionError message="Your latest prediction could not be retrieved. Try refreshing shortly." />
        ) : prediction ? (
          <>
            <div className={styles.stressResult}>
              <div>
                <span className={styles.resultLabel}>Current status</span>
                <strong>{formatStressLabel(prediction.label)}</strong>
              </div>
              <div className={styles.confidenceBlock}>
                <span>Confidence</span>
                <strong>{Math.round(prediction.confidence * 100)}%</strong>
              </div>
            </div>

            <dl className={styles.metaGrid}>
              <div>
                <dt>Recorded</dt>
                <dd>{formatDateTime(prediction.recorded_at, timeZone)}</dd>
              </div>
              <div>
                <dt>Model</dt>
                <dd>{prediction.model_version}</dd>
              </div>
            </dl>

            {prediction.out_of_training_range ? (
              <div className={styles.rangeNotice} role="status">
                <WarningCircle size={18} weight="duotone" aria-hidden="true" />
                <span>
                  Some inputs were outside the model training range, so interpret
                  this result with extra care.
                </span>
              </div>
            ) : null}
          </>
        ) : (
          <div className={styles.emptyCompact}>
            <strong>No stress readings yet</strong>
            <p>
              Complete a reading in the Android app and allow it to synchronize.
            </p>
          </div>
        )}
      </article>

      <div className={styles.vitalGrid} aria-label="Latest synchronized vitals">
        <VitalCard
          tone="heart"
          icon={<Heartbeat size={23} weight="duotone" />}
          label="Heart rate"
          value={prediction ? `${Math.round(prediction.heart_rate)} bpm` : "Not available"}
          detail="At the latest reading"
          unavailable={predictionFailed}
        />
        <VitalCard
          tone="steps"
          icon={<Footprints size={23} weight="duotone" />}
          label="Steps and activity"
          value={prediction ? prediction.daily_steps.toLocaleString("en-US") : "Not available"}
          detail={
            prediction
              ? `Activity input ${prediction.activity_level.toFixed(1)}`
              : "No synchronized reading"
          }
          unavailable={predictionFailed}
        />
        <VitalCard
          tone="sleep"
          icon={<MoonStars size={23} weight="duotone" />}
          label="Sleep"
          value={prediction ? `${prediction.sleep_hours.toFixed(1)} hr` : "Not available"}
          detail="Model input from mobile"
          unavailable={predictionFailed}
        />
      </div>
    </section>
  );
}

type VitalCardProps = {
  tone: "heart" | "steps" | "sleep";
  icon: React.ReactNode;
  label: string;
  value: string;
  detail: string;
  unavailable: boolean;
};

function VitalCard({
  tone,
  icon,
  label,
  value,
  detail,
  unavailable,
}: VitalCardProps) {
  return (
    <article className={`${styles.vitalCard} ${styles[`vital_${tone}`]}`}>
      <span className={styles.vitalIcon} aria-hidden="true">
        {icon}
      </span>
      <div>
        <p>{label}</p>
        <strong>{unavailable ? "Temporarily unavailable" : value}</strong>
        <small>{unavailable ? "Try refreshing shortly" : detail}</small>
      </div>
    </article>
  );
}
