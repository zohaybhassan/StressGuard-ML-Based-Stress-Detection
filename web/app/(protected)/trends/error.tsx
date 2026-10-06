"use client";

import { WarningCircle } from "@phosphor-icons/react";
import styles from "@/components/trends/trends.module.css";

export default function TrendsError({ reset }: { reset: () => void }) {
  return (
    <div className={styles.trends}>
      <section className={styles.configurationError} role="alert">
        <WarningCircle size={30} weight="duotone" aria-hidden="true" />
        <h1>We could not prepare your trends</h1>
        <p>
          No health values were logged. Retry the request, or return later if the
          problem continues.
        </p>
        <button type="button" onClick={reset}>
          Try again
        </button>
      </section>
    </div>
  );
}
