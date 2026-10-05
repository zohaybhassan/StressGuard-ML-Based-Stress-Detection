"use client";

import {
  Footprints,
  Heart,
  Lightning,
  MoonStars,
} from "@phosphor-icons/react";
import dynamic from "next/dynamic";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import styles from "./landing-reference.module.css";

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

const reducedMotionQuery = "(prefers-reduced-motion: reduce)";

function subscribeToReducedMotion(callback: () => void) {
  const mediaQuery = window.matchMedia(reducedMotionQuery);
  mediaQuery.addEventListener("change", callback);
  return () => mediaQuery.removeEventListener("change", callback);
}

function getReducedMotionSnapshot() {
  return window.matchMedia(reducedMotionQuery).matches;
}

export function VitalsPreview() {
  const sectionRef = useRef<HTMLElement>(null);
  const [period, setPeriod] = useState<Period>("Day");
  const [isRevealed, setIsRevealed] = useState(false);
  const prefersReducedMotion = useSyncExternalStore(
    subscribeToReducedMotion,
    getReducedMotionSnapshot,
    () => false,
  );
  const scale = periodScale[period];

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsRevealed(true);
          observer.disconnect();
        }
      },
      { threshold: 0.2, rootMargin: "0px 0px -10% 0px" },
    );

    observer.observe(section);
    return () => observer.disconnect();
  }, []);

  return (
    <section ref={sectionRef} id="health-preview" className={styles.vitalsSection}>
      <div className={"page-container " + styles.vitalsContent}>
        <div className={styles.vitalsHeader}>
          <div>
            <h2 className={styles.sectionTitle}>Your Health at a Glance</h2>
            <p className="body-copy">
              See your key metrics and trends, all in one place.
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

        <div className={styles.vitalsGrid} data-revealed={isRevealed}>
          <VitalChart
            index={0}
            icon={Lightning}
            label="Stress Level"
            value={Math.round(45 * scale) + "%"}
            helper="Normal"
            data={lineData}
            chart="area"
            tone="stress"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={1}
            icon={Heart}
            label="Heart Rate"
            value={Math.round(94 * scale) + " bpm"}
            helper="Today&apos;s average"
            data={heartData}
            chart="area"
            tone="heart"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={2}
            icon={Footprints}
            label="Steps"
            value={Math.round(4974 * scale).toLocaleString()}
            helper="Today"
            data={stepData}
            chart="bar"
            tone="steps"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={3}
            icon={MoonStars}
            label="Sleep"
            value={(7.5 * scale).toFixed(1) + " hrs"}
            helper="Total sleep"
            data={sleepData}
            chart="bar"
            tone="sleep"
            animateChart={isRevealed && !prefersReducedMotion}
          />
        </div>
      </div>
    </section>
  );
}

type VitalChartProps = {
  index: number;
  icon: typeof Heart;
  label: string;
  value: string;
  helper: string;
  data: { index: number; value: number }[];
  chart: "area" | "bar";
  tone: "stress" | "heart" | "steps" | "sleep";
  animateChart: boolean;
};

function VitalChart({
  index,
  icon: Icon,
  label,
  value,
  helper,
  data,
  chart,
  tone,
  animateChart,
}: VitalChartProps) {
  return (
    <article className={styles.vitalCard} data-tone={tone} data-vital-index={index}>
      <div className={styles.vitalLabel}>
        <span>
          <Icon size={17} weight="fill" aria-hidden />
        </span>
        <strong>{label}</strong>
      </div>
      <strong className={styles.vitalValue}>{value}</strong>
      <span className={styles.vitalHelper}>{helper}</span>
      <div className={styles.chart} aria-hidden>
        <VitalChartGraphic
          key={animateChart ? "animated" : "static"}
          chart={chart}
          data={data}
          animate={animateChart}
          animationBegin={180 + index * 150}
        />
      </div>
      <div className={styles.chartTicks} aria-hidden>
        <span>12AM</span><span>6AM</span><span>12PM</span><span>6PM</span>
      </div>
      <p className="sr-only">
        {label} sample for this {helper.toLowerCase()}: {value}.
      </p>
    </article>
  );
}
