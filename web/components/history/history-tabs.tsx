import Link from "next/link";
import type { HistoryFilters, HistoryTab } from "@/lib/history/types";
import { historyQueryString } from "@/lib/history/validation";
import styles from "./history.module.css";

const tabs: Array<{ value: HistoryTab; label: string }> = [
  { value: "predictions", label: "Predictions" },
  { value: "alerts", label: "Alerts / Feedback" },
  { value: "workouts", label: "Workouts" },
];

export function HistoryTabs({ filters }: { filters: HistoryFilters }) {
  return (
    <nav className={styles.tabs} aria-label="History sections">
      {tabs.map((tab) => {
        const active = filters.tab === tab.value;
        const query = historyQueryString(filters, {
          tab: tab.value,
          page: 1,
          detail: null,
        });
        return (
          <Link
            key={tab.value}
            href={`/history?${query}`}
            aria-current={active ? "page" : undefined}
            className={active ? styles.activeTab : undefined}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
