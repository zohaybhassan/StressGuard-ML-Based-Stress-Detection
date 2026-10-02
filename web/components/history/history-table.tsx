"use client";

import { useCallback, useMemo } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  type ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { Eye } from "@phosphor-icons/react";
import type { HistoryItem, HistoryTab } from "@/lib/history/types";
import {
  formatHistoryDateTime,
  formatHistoryDuration,
  formatHistoryLabel,
} from "./format";
import styles from "./history.module.css";

function itemKey(item: HistoryItem) {
  return `${item.kind}:${item.id}`;
}

function itemStatus(item: HistoryItem) {
  if (item.kind === "prediction") return formatHistoryLabel(item.label);
  if (item.kind === "alert") return item.dismissed ? "Dismissed" : "Recorded";
  if (item.kind === "feedback") {
    return item.confirmedStressed ? "Stress confirmed" : "Stress not confirmed";
  }
  return formatHistoryLabel(item.status);
}

function itemContext(item: HistoryItem) {
  if (item.kind === "prediction") {
    return `${Math.round(item.confidence * 100)}% confidence`;
  }
  if (item.kind === "alert") return item.reason;
  if (item.kind === "feedback") {
    return `${formatHistoryLabel(item.promptSource)}${item.severity ? `, severity ${item.severity}/10` : ""}`;
  }
  return formatHistoryDuration(item.durationMs);
}

function itemMetrics(item: HistoryItem) {
  if (item.kind === "prediction") {
    return `${Math.round(item.heartRate)} bpm, ${item.sleepHours.toFixed(1)} hr sleep`;
  }
  if (item.kind === "alert") {
    return `${item.highCountInWindow} of ${item.windowSize} high readings`;
  }
  if (item.kind === "feedback") {
    return `${Math.round(item.heartRate)} bpm at prediction`;
  }
  const heartRate =
    item.averageHeartRate === null ? "HR unavailable" : `${Math.round(item.averageHeartRate)} bpm avg`;
  const steps = item.stepCount === null ? "steps unavailable" : `${item.stepCount.toLocaleString("en-US")} steps`;
  return `${heartRate}, ${steps}`;
}

export function HistoryTable({
  items,
  tab,
  timeZone,
}: {
  items: HistoryItem[];
  tab: HistoryTab;
  timeZone: string;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryString = searchParams.toString();

  const openDetail = useCallback((item: HistoryItem) => {
    const params = new URLSearchParams(queryString);
    params.set("detail", itemKey(item));
    router.push(`${pathname}?${params.toString()}`, { scroll: false });
  }, [pathname, queryString, router]);

  const columns = useMemo<ColumnDef<HistoryItem>[]>(
    () => [
      {
        id: "time",
        header: tab === "workouts" ? "Started" : "Recorded",
        cell: ({ row }) => formatHistoryDateTime(row.original.occurredAt, timeZone),
      },
      {
        id: "type",
        header: tab === "alerts" ? "Record" : "Status",
        cell: ({ row }) => (
          <div className={styles.statusCell}>
            {tab === "alerts" ? <small>{formatHistoryLabel(row.original.kind)}</small> : null}
            <span className={`${styles.statusBadge} ${styles[`status_${row.original.kind}`]}`}>
              {itemStatus(row.original)}
            </span>
          </div>
        ),
      },
      {
        id: "context",
        header: "Context",
        cell: ({ row }) => itemContext(row.original),
      },
      {
        id: "metrics",
        header: "Measurements",
        cell: ({ row }) => itemMetrics(row.original),
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => (
          <button
            className={styles.detailButton}
            type="button"
            onClick={() => openDetail(row.original)}
            aria-label={`View ${formatHistoryLabel(row.original.kind)} details`}
          >
            <Eye size={17} aria-hidden="true" />
            Details
          </button>
        ),
      },
    ],
    [openDetail, tab, timeZone],
  );

  // TanStack Table exposes intentionally stateful functions; this is its supported hook API.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map((header) => (
                  <th key={header.id} scope="col">
                    {header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map((row) => (
              <tr key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className={styles.mobileRecords}>
        {items.map((item) => (
          <article key={itemKey(item)}>
            <div>
              <small>{formatHistoryLabel(item.kind)}</small>
              <strong>{itemStatus(item)}</strong>
            </div>
            <time dateTime={item.occurredAt}>
              {formatHistoryDateTime(item.occurredAt, timeZone)}
            </time>
            <p>{itemContext(item)}</p>
            <span>{itemMetrics(item)}</span>
            <button type="button" onClick={() => openDetail(item)}>
              <Eye size={17} aria-hidden="true" />
              View details
            </button>
          </article>
        ))}
      </div>
    </>
  );
}
