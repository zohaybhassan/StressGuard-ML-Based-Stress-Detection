import { CloudArrowUp, ShieldCheck } from "@phosphor-icons/react/dist/ssr";
import type { DashboardSnapshot } from "@/lib/dashboard/types";
import { ActivityPanels } from "./activity-panels";
import { DashboardHeader } from "./dashboard-header";
import { QuickLinks } from "./quick-links";
import { StressOverview } from "./stress-overview";
import { TodaySummary } from "./today-summary";
import styles from "./dashboard.module.css";

type DashboardOverviewProps = {
  snapshot: DashboardSnapshot;
  now: Date;
  timeZone: string;
};

export function DashboardOverview({
  snapshot,
  now,
  timeZone,
}: DashboardOverviewProps) {
  return (
    <div className={styles.dashboard}>
      <DashboardHeader snapshot={snapshot} now={now} timeZone={timeZone} />

      <div className={styles.primaryGrid}>
        <StressOverview snapshot={snapshot} timeZone={timeZone} />
        <TodaySummary snapshot={snapshot} timeZone={timeZone} />
      </div>

      <aside className={styles.boundaryNotice} aria-label="How synchronization works">
        <span className={styles.noticeIcon} aria-hidden="true">
          <CloudArrowUp size={23} weight="duotone" />
        </span>
        <div>
          <strong>Your wearable works through the mobile app</strong>
          <p>
            StressGuard Web displays data synchronized from Android and Wear OS.
            Real-time sensing, on-device inference, and alerts continue in the mobile
            experience, not in this browser.
          </p>
        </div>
        <ShieldCheck className={styles.noticeShield} size={24} weight="duotone" aria-hidden="true" />
      </aside>

      <ActivityPanels snapshot={snapshot} now={now} timeZone={timeZone} />
      <QuickLinks />
    </div>
  );
}
