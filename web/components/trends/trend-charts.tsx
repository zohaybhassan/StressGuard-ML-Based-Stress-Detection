"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  Footprints,
  Heartbeat,
  MoonStars,
  Pulse,
} from "@phosphor-icons/react";
import type { DailyTrendPoint, TrendsDataset } from "@/lib/trends/types";
import { formatMetric, formatPercent } from "./format";
import styles from "./trends.module.css";

type MetricKey =
  | "stressedProportion"
  | "averageHeartRate"
  | "averageSleepHours"
  | "averageActivityLevel";

type MetricDefinition = {
  key: MetricKey;
  title: string;
  eyebrow: string;
  description: string;
  emptyDescription: string;
  color: string;
  unit: "percent" | "bpm" | "hours" | "activity";
  icon: typeof Pulse;
};

const metrics: MetricDefinition[] = [
  {
    key: "stressedProportion",
    title: "Stress overview",
    eyebrow: "Daily classification share",
    description:
      "The proportion of each day’s synchronized readings classified as stressed.",
    emptyDescription: "No stress readings were recorded for this range.",
    color: "var(--chart-stress)",
    unit: "percent",
    icon: Pulse,
  },
  {
    key: "averageHeartRate",
    title: "Heart rate trend",
    eyebrow: "Daily prediction average",
    description: "Average heart rate captured with each day’s stress predictions.",
    emptyDescription: "Heart-rate values are unavailable for this range.",
    color: "var(--chart-heart)",
    unit: "bpm",
    icon: Heartbeat,
  },
  {
    key: "averageSleepHours",
    title: "Sleep trend",
    eyebrow: "Synchronized model input",
    description:
      "Average sleep-hours input stored with each day’s stress predictions.",
    emptyDescription: "Sleep inputs are unavailable for this range.",
    color: "var(--chart-sleep)",
    unit: "hours",
    icon: MoonStars,
  },
  {
    key: "averageActivityLevel",
    title: "Activity trend",
    eyebrow: "Daily model activity input",
    description:
      "Average full-day activity input stored with each day’s predictions.",
    emptyDescription: "Activity inputs are unavailable for this range.",
    color: "var(--chart-steps)",
    unit: "activity",
    icon: Footprints,
  },
];

function formatValue(value: number | null, unit: MetricDefinition["unit"]) {
  if (unit === "percent") {
    return formatPercent(value);
  }
  return formatMetric(value, unit);
}

function chartValue(value: number | null, unit: MetricDefinition["unit"]) {
  return value === null ? null : unit === "percent" ? value * 100 : value;
}

function statusLabel(status: DailyTrendPoint["status"]) {
  if (status === "in-progress") return "In progress";
  if (status === "observed") return "Observed";
  return "Missing";
}

type TooltipContentProps = {
  active?: boolean;
  payload?: Array<{ payload: DailyTrendPoint & { chartMetric: number | null } }>;
  metric: MetricDefinition;
};

function TrendTooltip({ active, payload, metric }: TooltipContentProps) {
  const point = payload?.[0]?.payload;
  if (!active || !point) {
    return null;
  }

  return (
    <div className={styles.tooltip}>
      <strong>{point.fullLabel}</strong>
      <span>{formatValue(point[metric.key], metric.unit)}</span>
      {metric.key === "stressedProportion" && point.readingCount !== null ? (
        <small>
          {point.stressedReadingCount} of {point.readingCount} readings
        </small>
      ) : null}
      <small>{statusLabel(point.status)}</small>
    </div>
  );
}

function findExtreme(
  days: DailyTrendPoint[],
  key: MetricKey,
  direction: "highest" | "lowest",
) {
  const observed = days.filter((day) => day[key] !== null);
  if (observed.length === 0) return null;

  return observed.reduce((selected, day) => {
    const selectedValue = selected[key] as number;
    const dayValue = day[key] as number;
    return direction === "highest"
      ? dayValue > selectedValue
        ? day
        : selected
      : dayValue < selectedValue
        ? day
        : selected;
  });
}

function MetricChart({
  metric,
  days,
  range,
}: {
  metric: MetricDefinition;
  days: DailyTrendPoint[];
  range: number;
}) {
  const Icon = metric.icon;
  const observed = days.filter((day) => day[metric.key] !== null);
  const highest = findExtreme(days, metric.key, "highest");
  const lowest = findExtreme(days, metric.key, "lowest");
  const chartData = days.map((day) => ({
    ...day,
    chartMetric: chartValue(day[metric.key], metric.unit),
  }));

  return (
    <article className={styles.chartCard}>
      <header className={styles.chartHeader}>
        <div>
          <p className={styles.cardEyebrow}>{metric.eyebrow}</p>
          <h2>{metric.title}</h2>
          <p>{metric.description}</p>
        </div>
        <span className={styles.chartIcon} aria-hidden="true">
          <Icon size={24} weight="duotone" />
        </span>
      </header>

      {observed.length < 2 ? (
        <div className={styles.chartEmpty} role="status">
          <strong>
            {observed.length === 1 ? "One observed day so far" : "No chart data yet"}
          </strong>
          <p>
            {observed.length === 1
              ? "A trend needs at least two days with this measurement."
              : metric.emptyDescription}
          </p>
        </div>
      ) : (
        <>
          <div className={styles.chartCanvas} aria-hidden="true">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData} margin={{ top: 12, right: 10, bottom: 2, left: 0 }}>
                <CartesianGrid
                  stroke="var(--chart-grid)"
                  strokeDasharray="3 5"
                  vertical={false}
                />
                <XAxis
                  dataKey="shortLabel"
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: "var(--chart-axis)", fontSize: 11 }}
                  minTickGap={range === 30 ? 28 : 12}
                />
                <YAxis
                  axisLine={false}
                  tickLine={false}
                  width={45}
                  domain={metric.unit === "percent" ? [0, 100] : ["auto", "auto"]}
                  tick={{ fill: "var(--chart-axis)", fontSize: 11 }}
                  tickFormatter={(value: number) =>
                    metric.unit === "percent" ? `${value}%` : value.toLocaleString("en-US")
                  }
                />
                <Tooltip
                  cursor={{ stroke: "var(--color-border-strong)", strokeDasharray: "3 4" }}
                  content={<TrendTooltip metric={metric} />}
                />
                <Line
                  dataKey="chartMetric"
                  type="monotone"
                  stroke={metric.color}
                  strokeWidth={2.6}
                  connectNulls={false}
                  dot={{ r: range === 30 ? 2.4 : 3.5, fill: metric.color, strokeWidth: 0 }}
                  activeDot={{ r: 5, fill: metric.color, stroke: "var(--color-surface)", strokeWidth: 2 }}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className={styles.chartNarrative} aria-label={`${metric.title} text summary`}>
            <p>
              <strong>{observed.length}</strong>
              <span>days with data</span>
            </p>
            <p>
              <strong>{highest ? formatValue(highest[metric.key], metric.unit) : "Not available"}</strong>
              <span>highest on {highest?.shortLabel ?? "no day"}</span>
            </p>
            <p>
              <strong>{lowest ? formatValue(lowest[metric.key], metric.unit) : "Not available"}</strong>
              <span>lowest on {lowest?.shortLabel ?? "no day"}</span>
            </p>
            <p>
              <strong>{range - observed.length}</strong>
              <span>days without this metric</span>
            </p>
          </div>
        </>
      )}

      <details className={styles.dataTable}>
        <summary>View daily values</summary>
        <div>
          <table>
            <caption className="sr-only">{metric.title} daily values</caption>
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Status</th>
                <th scope="col">Value</th>
                {metric.key === "stressedProportion" ? <th scope="col">Readings</th> : null}
              </tr>
            </thead>
            <tbody>
              {days.map((day) => (
                <tr key={day.date}>
                  <th scope="row">{day.fullLabel}</th>
                  <td>{statusLabel(day.status)}</td>
                  <td>{formatValue(day[metric.key], metric.unit)}</td>
                  {metric.key === "stressedProportion" ? (
                    <td>
                      {day.readingCount === null
                        ? "Not recorded"
                        : `${day.stressedReadingCount} of ${day.readingCount}`}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </article>
  );
}

export function TrendCharts({ dataset }: { dataset: TrendsDataset }) {
  return (
    <section className={styles.chartGrid} aria-label="Trend charts">
      {metrics.map((metric) => (
        <MetricChart
          key={metric.key}
          metric={metric}
          days={dataset.days}
          range={dataset.range}
        />
      ))}
    </section>
  );
}
