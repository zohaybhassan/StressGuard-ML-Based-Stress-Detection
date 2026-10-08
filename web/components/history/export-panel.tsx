"use client";

import { useState } from "react";
import { DownloadSimple, FileCsv, ShieldCheck } from "@phosphor-icons/react";
import { reportErrorResponse } from "@/lib/reports/validation";
import styles from "./history.module.css";

type ExportState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; downloadUrl: string; expiresAt: string };

export function ExportPanel({ defaultFrom, defaultTo, timeZone }: {
  defaultFrom: string;
  defaultTo: string;
  timeZone: string;
}) {
  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(defaultTo);
  const [state, setState] = useState<ExportState>({ status: "idle" });

  async function requestReport() {
    setState({ status: "loading" });
    try {
      const response = await fetch("/api/reports", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ from, to, timeZone }),
      });
      const body = (await response.json()) as { code?: string; downloadUrl?: string; expiresAt?: string };
      if (!response.ok || !body.downloadUrl || !body.expiresAt) {
        setState({ status: "error", message: reportErrorResponse(body.code ?? "generation_failed") });
        return;
      }
      setState({ status: "ready", downloadUrl: body.downloadUrl, expiresAt: body.expiresAt });
    } catch {
      setState({ status: "error", message: reportErrorResponse("generation_failed") });
    }
  }

  return (
    <section className={styles.exportPanel} aria-labelledby="export-heading">
      <div className={styles.exportIntro}>
        <span aria-hidden="true"><FileCsv size={25} weight="duotone" /></span>
        <div>
          <p className={styles.eyebrow}>Private report</p>
          <h2 id="export-heading">Export history as CSV</h2>
          <p>Includes predictions, alerts, feedback, and workouts in your selected local-date range.</p>
        </div>
      </div>
      <div className={styles.exportControls}>
        <label><span>From</span><input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
        <label><span>To</span><input type="date" value={to} onChange={(event) => setTo(event.target.value)} /></label>
        <button type="button" onClick={requestReport} disabled={state.status === "loading" || !from || !to}>
          <DownloadSimple size={17} aria-hidden="true" />
          {state.status === "loading" ? "Preparing…" : "Prepare CSV"}
        </button>
      </div>
      <p className={styles.exportPrivacy}>
        <ShieldCheck size={16} weight="duotone" aria-hidden="true" />
        The file is encrypted in private storage, deleted within 24 hours, and its download link expires in 10 minutes.
      </p>
      <div className={styles.exportStatus} aria-live="polite">
        {state.status === "error" ? <p role="alert">{state.message}</p> : null}
        {state.status === "ready" ? (
          <p>Your report is ready. <a href={state.downloadUrl}>Download CSV</a>{" "}
            <small>Link expires {new Intl.DateTimeFormat("en", { timeStyle: "short", timeZone }).format(new Date(state.expiresAt))}.</small>
          </p>
        ) : null}
      </div>
    </section>
  );
}
