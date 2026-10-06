import styles from "./history.module.css";

export function HistoryLoadingState() {
  return <div className={styles.history} aria-label="Loading history" aria-busy="true">
    <div className={styles.loadingHeader} /><div className={styles.loadingTabs} />
    <div className={styles.loadingFilters} /><div className={styles.loadingTable} />
  </div>;
}
