"use client";

import styles from "@/components/history/history.module.css";

export default function HistoryError({ reset }: { reset: () => void }) {
  return <section className={styles.configurationError} role="alert"><h1>History needs another try</h1>
    <p>We could not finish loading this view. Your saved data was not changed.</p>
    <button className="button-secondary" type="button" onClick={reset}>Try again</button></section>;
}
