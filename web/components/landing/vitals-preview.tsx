"use client";

import {
  Footprints,
  Heart,
  Lightning,
  MoonStars,
} from "@phosphor-icons/react";
import dynamic from "next/dynamic";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { sampleTrends, type SamplePeriod } from "./sample-trends";
import styles from "./landing-reference.module.css";

const VitalChartGraphic = dynamic(
  () => import("./vital-chart-graphic").then((module) => module.VitalChartGraphic),
  {
    ssr: false,
    loading: () => <span className={styles.chartPlaceholder} />,
  },
);

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
  const [period, setPeriod] = useState<SamplePeriod>("Day");
  const [isRevealed, setIsRevealed] = useState(false);
  const prefersReducedMotion = useSyncExternalStore(
    subscribeToReducedMotion,
    getReducedMotionSnapshot,
    () => false,
  );
  const snapshot = sampleTrends[period];

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
            <h2 className={styles.sectionTitle}>See the bigger picture</h2>
            <p className={"body-copy " + styles.previewDescription}>
              Explore how your wellness trends could look over time. These example readings are
              illustrative, not your personal health data.
            </p>
          </div>
          <div className={styles.periodControl} role="group" aria-label="Sample trend time range">
            {(Object.keys(sampleTrends) as SamplePeriod[]).map((item) => (
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

        <p className="sr-only" aria-live="polite">{period} sample trends selected</p>
        <div className={styles.vitalsGrid} data-revealed={isRevealed} data-period={period}>
          <VitalChart
            index={0}
            icon={Lightning}
            label="Stress Level"
            value={snapshot.metrics.stress.value}
            helper={snapshot.metrics.stress.helper}
            data={snapshot.metrics.stress.values.map((value, index) => ({ index, value }))}
            ticks={snapshot.ticks}
            period={period}
            chart="area"
            tone="stress"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={1}
            icon={Heart}
            label="Heart Rate"
            value={snapshot.metrics.heart.value}
            helper={snapshot.metrics.heart.helper}
            data={snapshot.metrics.heart.values.map((value, index) => ({ index, value }))}
            ticks={snapshot.ticks}
            period={period}
            chart="area"
            tone="heart"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={2}
            icon={Footprints}
            label="Steps"
            value={snapshot.metrics.steps.value}
            helper={snapshot.metrics.steps.helper}
            data={snapshot.metrics.steps.values.map((value, index) => ({ index, value }))}
            ticks={snapshot.ticks}
            period={period}
            chart="bar"
            tone="steps"
            animateChart={isRevealed && !prefersReducedMotion}
          />
          <VitalChart
            index={3}
            icon={MoonStars}
            label="Sleep"
            value={snapshot.metrics.sleep.value}
            helper={snapshot.metrics.sleep.helper}
            data={snapshot.metrics.sleep.values.map((value, index) => ({ index, value }))}
            ticks={snapshot.ticks}
            period={period}
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
  ticks: readonly [string, string, string, string];
  period: SamplePeriod;
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
  ticks,
  period,
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
          key={`${period}-${animateChart ? "animated" : "static"}`}
          chart={chart}
          data={data}
          animate={animateChart}
          animationBegin={180 + index * 150}
        />
      </div>
      <div className={styles.chartTicks} aria-hidden>
        {ticks.map((tick) => <span key={tick}>{tick}</span>)}
      </div>
      <p className="sr-only">
        {label} sample for {period.toLowerCase()}: {value}.
      </p>
    </article>
  );
}
