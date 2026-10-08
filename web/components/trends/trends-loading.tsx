import styles from "./trends.module.css";

export function TrendsLoadingState() {
  return (
    <div className={`${styles.trends} ${styles.loading}`} aria-busy="true">
      <span className="sr-only">Loading synchronized trends.</span>
      <div className={styles.loadingHeader} />
      <div className={styles.loadingSummary}>
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} />
        ))}
      </div>
      <div className={styles.loadingCharts}>
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} />
        ))}
      </div>
    </div>
  );
}
