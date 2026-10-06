import Link from "next/link";
import type { TrendRange } from "@/lib/trends/types";
import styles from "./trends.module.css";

export function RangeSelector({ range }: { range: TrendRange }) {
  return (
    <nav className={styles.rangeSelector} aria-label="Trend date range">
      <Link
        href="/trends?range=7"
        aria-current={range === 7 ? "page" : undefined}
        className={range === 7 ? styles.rangeActive : undefined}
      >
        7 Days
      </Link>
      <Link
        href="/trends?range=30"
        aria-current={range === 30 ? "page" : undefined}
        className={range === 30 ? styles.rangeActive : undefined}
      >
        30 Days
      </Link>
    </nav>
  );
}
