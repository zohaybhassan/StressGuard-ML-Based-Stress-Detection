"use client";

import { useEffect, useRef } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { X } from "@phosphor-icons/react";
import type { HistoryItem } from "@/lib/history/types";
import {
  formatHistoryDateTime,
  formatHistoryDuration,
  formatHistoryLabel,
} from "./format";
import styles from "./history.module.css";

type DetailRow = { label: string; value: string };

function detailRows(item: HistoryItem, timeZone: string): DetailRow[] {
  if (item.kind === "prediction") {
    return [
      { label: "Recorded", value: formatHistoryDateTime(item.occurredAt, timeZone) },
      { label: "Stress result", value: formatHistoryLabel(item.label) },
      { label: "Confidence", value: `${Math.round(item.confidence * 100)}%` },
      { label: "Heart rate", value: `${Math.round(item.heartRate)} bpm` },
      { label: "Sleep input", value: `${item.sleepHours.toFixed(1)} hr` },
      { label: "Activity input", value: item.activityLevel.toLocaleString("en-US") },
      { label: "Steps snapshot", value: item.dailySteps.toLocaleString("en-US") },
      { label: "Model version", value: item.modelVersion },
      {
        label: "Input range",
        value: item.outOfTrainingRange ? "Outside training range" : "Within training range",
      },
    ];
  }

  if (item.kind === "alert") {
    return [
      { label: "Alert time", value: formatHistoryDateTime(item.occurredAt, timeZone) },
      { label: "Status", value: item.dismissed ? "Dismissed" : "Recorded" },
      { label: "Reason", value: item.reason },
      { label: "Window", value: `${item.highCountInWindow} of ${item.windowSize} high readings` },
      { label: "Model version", value: item.modelVersion },
    ];
  }

  if (item.kind === "feedback") {
    return [
      { label: "Responded", value: formatHistoryDateTime(item.occurredAt, timeZone) },
      { label: "Prompt source", value: formatHistoryLabel(item.promptSource) },
      { label: "Alert coordinate", value: formatHistoryDateTime(item.alertFiredAt, timeZone) },
      { label: "Prediction time", value: formatHistoryDateTime(item.predictionRecordedAt, timeZone) },
      { label: "Predicted result", value: formatHistoryLabel(item.predictedLabel) },
      { label: "Confidence", value: `${Math.round(item.confidence * 100)}%` },
      { label: "User response", value: item.confirmedStressed ? "Stress confirmed" : "Stress not confirmed" },
      { label: "Severity", value: item.severity === null ? "Not applicable" : `${item.severity} of 10` },
      { label: "Heart rate", value: `${Math.round(item.heartRate)} bpm` },
      { label: "Sleep input", value: `${item.sleepHours.toFixed(1)} hr` },
      { label: "Activity input", value: item.activityLevel.toLocaleString("en-US") },
      { label: "Steps snapshot", value: item.dailySteps.toLocaleString("en-US") },
      { label: "Model version", value: item.modelVersion },
    ];
  }

  return [
    { label: "Started", value: formatHistoryDateTime(item.occurredAt, timeZone) },
    { label: "Ended", value: formatHistoryDateTime(item.endedAt, timeZone) },
    { label: "Planned end", value: formatHistoryDateTime(item.plannedEndAt, timeZone) },
    { label: "Status", value: formatHistoryLabel(item.status) },
    { label: "Duration", value: formatHistoryDuration(item.durationMs) },
    {
      label: "Steps",
      value: item.stepCount === null ? "Not available" : item.stepCount.toLocaleString("en-US"),
    },
    {
      label: "Average heart rate",
      value: item.averageHeartRate === null ? "Not available" : `${Math.round(item.averageHeartRate)} bpm`,
    },
    {
      label: "Heart-rate range",
      value:
        item.minHeartRate === null || item.maxHeartRate === null
          ? "Not available"
          : `${item.minHeartRate}–${item.maxHeartRate} bpm`,
    },
    { label: "Paused time", value: formatHistoryDuration(item.totalPausedMs) },
  ];
}

export function HistoryDetailDialog({
  item,
  timeZone,
}: {
  item: HistoryItem | null;
  timeZone: string;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const close = () => {
    const dialog = dialogRef.current;
    if (dialog?.open && typeof dialog.close === "function") dialog.close();
    const params = new URLSearchParams(searchParams.toString());
    params.delete("detail");
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog || !item) return;
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
  }, [item]);

  if (!item) return null;
  const rows = detailRows(item, timeZone);

  return (
    <dialog
      ref={dialogRef}
      className={styles.detailDialog}
      aria-labelledby="history-detail-title"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <div>
        <header>
          <div>
            <p>History detail</p>
            <h2 id="history-detail-title">{formatHistoryLabel(item.kind)}</h2>
          </div>
          <button type="button" onClick={close} aria-label="Close history detail">
            <X size={20} aria-hidden="true" />
          </button>
        </header>
        <dl>
          {rows.map((row) => (
            <div key={row.label}>
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
        <p className={styles.detailNote}>
          These are synchronized records from the Android and Wear OS experience.
        </p>
      </div>
    </dialog>
  );
}
