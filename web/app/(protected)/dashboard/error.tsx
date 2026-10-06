"use client";

import { WarningCircle } from "@phosphor-icons/react";
import styles from "@/components/dashboard/dashboard.module.css";

type DashboardErrorProps = {
  reset: () => void;
};

export default function DashboardError({ reset }: DashboardErrorProps) {
  return (
    <div className={styles.dashboard}>
      <section className={styles.configurationError} role="alert">
        <WarningCircle size={30} weight="duotone" aria-hidden="true" />
        <h1>We could not prepare your dashboard</h1>
        <p>
          Your data remains private. Please retry the request, or return a little
          later if the problem continues.
        </p>
        <button type="button" onClick={reset}>
          Try again
        </button>
      </section>
    </div>
  );
}
