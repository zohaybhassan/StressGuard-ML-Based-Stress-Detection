import {
  CheckCircle,
  ClockCountdown,
  CloudSlash,
} from "@phosphor-icons/react/dist/ssr";
import type { DashboardSnapshot } from "@/lib/dashboard/types";
import { formatDateTime, getGreeting } from "./format";
import styles from "./dashboard.module.css";

const freshnessContent = {
  fresh: {
    label: "Fresh reading",
    detail: "Recorded within 45 minutes",
    icon: CheckCircle,
  },
  delayed: {
    label: "Delayed data",
    detail: "Latest reading is over 45 minutes old",
    icon: ClockCountdown,
  },
  unavailable: {
    label: "No reading yet",
    detail: "Open the mobile app to begin synchronizing",
    icon: CloudSlash,
  },
} as const;

type DashboardHeaderProps = {
  snapshot: DashboardSnapshot;
  now: Date;
  timeZone: string;
};

export function DashboardHeader({
  snapshot,
  now,
  timeZone,
}: DashboardHeaderProps) {
  const displayName = snapshot.profile?.display_name?.trim();
  const firstName = displayName?.split(/\s+/)[0];
  const date = new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "long",
    month: "long",
    day: "numeric",
  }).format(now);
  const freshness = freshnessContent[snapshot.freshness];
  const FreshnessIcon = freshness.icon;

  return (
    <header className={styles.header}>
      <div>
        <p className={styles.eyebrow}>{date}</p>
        <h1 className={styles.pageTitle}>
          {getGreeting(now, timeZone)}{firstName ? `, ${firstName}` : ""}
        </h1>
        <p className={styles.pageIntro}>
          A calm overview of the health signals synchronized to your account.
        </p>
      </div>

      <div
        className={`${styles.freshness} ${styles[`freshness_${snapshot.freshness}`]}`}
        aria-label={`${freshness.label}. ${freshness.detail}`}
      >
        <FreshnessIcon size={20} weight="duotone" aria-hidden="true" />
        <span>
          <strong>{freshness.label}</strong>
          <small>{freshness.detail}</small>
        </span>
      </div>

      <p className={styles.syncLine}>
        Latest cloud record: {formatDateTime(snapshot.latestCloudRecordAt, timeZone)}
      </p>
    </header>
  );
}
