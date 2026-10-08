import styles from "./dashboard.module.css";

export function DashboardLoadingState() {
  return (
    <div className={`${styles.dashboard} ${styles.loading}`} aria-busy="true">
      <span className="sr-only">Preparing your dashboard in your local time zone.</span>
      <div className={styles.loadingHeader} />
      <div className={styles.loadingGrid}>
        <div className={styles.loadingPrimary} />
        <div className={styles.loadingPrimary} />
      </div>
      <div className={styles.loadingNotice} />
      <div className={styles.loadingPanels}>
        <div className={styles.loadingPanel} />
        <div className={styles.loadingPanel} />
      </div>
    </div>
  );
}
