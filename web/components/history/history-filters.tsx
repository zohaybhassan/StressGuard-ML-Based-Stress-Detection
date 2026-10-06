import Link from "next/link";
import { FunnelSimple, X } from "@phosphor-icons/react/dist/ssr";
import type { HistoryFilters } from "@/lib/history/types";
import styles from "./history.module.css";

export function HistoryFiltersForm({ filters }: { filters: HistoryFilters }) {
  return (
    <form className={styles.filters} method="get" action="/history">
      <input type="hidden" name="tab" value={filters.tab} />
      <div className={styles.filterHeading}>
        <FunnelSimple size={19} weight="duotone" aria-hidden="true" />
        <strong>Filter history</strong>
      </div>

      <label>
        <span>From</span>
        <input type="date" name="from" defaultValue={filters.from ?? ""} />
      </label>
      <label>
        <span>To</span>
        <input type="date" name="to" defaultValue={filters.to ?? ""} />
      </label>

      {filters.tab === "predictions" ? (
        <>
          <label>
            <span>Stress result</span>
            <select name="label" defaultValue={filters.predictionLabel}>
              <option value="all">All results</option>
              <option value="stressed">Stressed</option>
              <option value="not_stressed">Not stressed</option>
              <option value="high_stress">High stress</option>
            </select>
          </label>
          <label>
            <span>Input range</span>
            <select name="inputRange" defaultValue={filters.inputRange}>
              <option value="all">All readings</option>
              <option value="warning">Outside training range</option>
              <option value="within">Within training range</option>
            </select>
          </label>
        </>
      ) : null}

      {filters.tab === "alerts" ? (
        <>
          <label>
            <span>Record type</span>
            <select name="records" defaultValue={filters.alertRecords}>
              <option value="all">Alerts and feedback</option>
              <option value="alerts">Alerts only</option>
              <option value="feedback">Feedback only</option>
            </select>
          </label>
          <label>
            <span>Alert status</span>
            <select name="dismissed" defaultValue={filters.dismissed}>
              <option value="all">All alert statuses</option>
              <option value="no">Recorded</option>
              <option value="yes">Dismissed</option>
            </select>
          </label>
          <label>
            <span>Feedback source</span>
            <select name="source" defaultValue={filters.feedbackSource}>
              <option value="all">All feedback sources</option>
              <option value="high_stress_alert">High-stress alert</option>
              <option value="periodic_check_in">Periodic check-in</option>
            </select>
          </label>
        </>
      ) : null}

      {filters.tab === "workouts" ? (
        <label>
          <span>Workout status</span>
          <select name="status" defaultValue={filters.workoutStatus}>
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="paused">Paused</option>
            <option value="completed">Completed</option>
          </select>
        </label>
      ) : null}

      <div className={styles.filterActions}>
        <button type="submit">Apply filters</button>
        <Link href={`/history?tab=${filters.tab}`}>
          <X size={15} aria-hidden="true" />
          Clear
        </Link>
      </div>
    </form>
  );
}
