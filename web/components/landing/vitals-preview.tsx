"use client";

import {
  Footprints,
  Heart,
  Lightning,
  MoonStars,
} from "@phosphor-icons/react";
import dynamic from "next/dynamic";
import { useState } from "react";

import styles from "./landing.module.css";

const VitalChartGraphic = dynamic(
  () => import("./vital-chart-graphic").then((module) => module.VitalChartGraphic),
  {
    ssr: false,
    loading: () => <span className={styles.chartPlaceholder} />,
  },
);

type Period = "Day" | "Week" | "Month" | "Year";

const periodScale: Record<Period, number> = {
  Day: 1,
  Week: 0.94,
  Month: 0.88,
  Year: 0.82,
};

const lineData = [
  22, 20, 25, 21, 31, 29, 37, 33, 52, 44, 39, 48, 45, 61, 56, 43, 50, 46,
].map((value, index) => ({ index, value }));

const heartData = [
  76, 79, 74, 81, 78, 83, 80, 86, 82, 88, 84, 91, 85, 93, 87, 90, 86, 94,
].map((value, index) => ({ index, value }));

const stepData = [
  2, 4, 3, 5, 4, 6, 7, 5, 8, 9, 7, 11, 10, 13, 9, 12, 8, 10,
].map((value, index) => ({ index, value }));

const sleepData = [
  4, 6, 5, 7, 6, 8, 7, 10, 8, 9, 7, 11, 9, 12, 10, 13, 11, 14,
].map((value, index) => ({ index, value }));

export function VitalsPreview() {
  const [period, setPeriod] = useState<Period>("Day");
  const scale = periodScale[period];

  return (
    <section id="health-preview" className={"section-space " + styles.vitalsSection}>
      <div className="page-container">
        <div className={styles.vitalsHeader}>
          <div>
            <p className="eyebrow">Illustrative preview</p>
            <h2 className="section-title">Your health at a glance</h2>
            <p className="body-copy">
              Read the signals together, with time and context instead of isolated numbers.
            </p>
          </div>
          <div className={styles.periodControl} aria-label="Preview time range">
            {(Object.keys(periodScale) as Period[]).map((item) => (
              <button
                key={item}
                type="button"
                aria-pressed={period === item}
                onClick={() => setPeriod(item)}
              >
                {item}
              </button>
            ))}
          </div>
        </div>

        <div className={styles.vitalsGrid}>
          <VitalChart
            icon={Lightning}
            label="Stress level"
            value={Math.round(45 * scale) + "%"}
            helper="Normal"
            data={lineData}
            chart="area"
            tone="stress"
          />
          <VitalChart
            icon={Heart}
            label="Heart rate"
            value={Math.round(94 * scale) + " bpm"}
            helper="Sample average"
            data={heartData}
            chart="area"
            tone="heart"
          />
          <VitalChart
            icon={Footprints}
            label="Steps"
            value={Math.round(4974 * scale).toLocaleString()}
            helper="Sample total"
            data={stepData}
            chart="bar"
            tone="steps"
          />
          <VitalChart
            icon={MoonStars}
            label="Sleep"
            value={(7.5 * scale).toFixed(1) + " hrs"}
            helper="Sample duration"
            data={sleepData}
            chart="bar"
            tone="sleep"
          />
        </div>
      </div>
    </section>
  );
}

type VitalChartProps = {
  icon: typeof Heart;
  label: string;
  value: string;
  helper: string;
  data: { index: number; value: number }[];
  chart: "area" | "bar";
  tone: "stress" | "heart" | "steps" | "sleep";
};

function VitalChart({
  icon: Icon,
  label,
  value,
  helper,
  data,
  chart,
  tone,
}: VitalChartProps) {
  return (
    <article className={styles.vitalCard} data-tone={tone}>
      <div className={styles.vitalLabel}>
        <span>
          <Icon size={17} weight="fill" aria-hidden />
        </span>
        <strong>{label}</strong>
      </div>
      <strong className={styles.vitalValue}>{value}</strong>
      <span className={styles.vitalHelper}>{helper}</span>
      <div className={styles.chart} aria-hidden>
        <VitalChartGraphic chart={chart} data={data} />
      </div>
      <p className="sr-only">
        {label} sample for this {helper.toLowerCase()}: {value}.
      </p>
    </article>
  );
}
