import Link from "next/link";
import { ArrowLeft, ArrowRight } from "@phosphor-icons/react/dist/ssr";
import type { HistoryFilters, HistoryPageData } from "@/lib/history/types";
import { historyQueryString } from "@/lib/history/validation";
import styles from "./history.module.css";

export function HistoryPagination({
  data,
  filters,
}: {
  data: HistoryPageData;
  filters: HistoryFilters;
}) {
  return (
    <nav className={styles.pagination} aria-label="History pages">
      <p>
        Page <strong>{data.page}</strong> of <strong>{data.totalPages}</strong>
        <span>{data.total.toLocaleString("en-US")} total records</span>
      </p>
      <div>
        {data.hasPrevious ? (
          <Link
            href={`/history?${historyQueryString(filters, {
              page: data.page - 1,
              detail: null,
            })}`}
          >
            <ArrowLeft size={16} aria-hidden="true" />
            Previous
          </Link>
        ) : (
          <span aria-disabled="true">Previous</span>
        )}
        {data.hasNext ? (
          <Link
            href={`/history?${historyQueryString(filters, {
              page: data.page + 1,
              detail: null,
            })}`}
          >
            Next
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        ) : (
          <span aria-disabled="true">Next</span>
        )}
      </div>
    </nav>
  );
}
