import {
  ChartLineUp,
  Footprints,
  Heart,
  House,
  MoonStars,
  Pulse,
} from "@phosphor-icons/react/dist/ssr";

import styles from "./landing.module.css";

export function ProductPreview() {
  return (
    <div className={styles.productStage}>
      <div className={styles.laptop} aria-label="Illustrative StressGuard dashboard preview">
        <div className={styles.laptopCamera} />
        <div className={styles.laptopScreen}>
          <aside className={styles.previewSidebar}>
            <span className={styles.previewBrand}>
              <Pulse size={14} weight="fill" aria-hidden />
              StressGuard
            </span>
            <span className={styles.previewNavActive}>
              <House size={13} weight="fill" aria-hidden />
              Home
            </span>
            <span>
              <ChartLineUp size={13} aria-hidden />
              Trends
            </span>
          </aside>
          <div className={styles.previewMain}>
            <div className={styles.previewWelcome}>
              <span>Sample preview</span>
              <strong>Your wellness overview</strong>
            </div>
            <div className={styles.previewGrid}>
              <div className={styles.stressPanel}>
                <span className={styles.previewLabel}>Current stress</span>
                <div className={styles.previewRing}>
                  <div>
                    <strong>45%</strong>
                    <span>Normal</span>
                  </div>
                </div>
                <small>Synchronized from Android</small>
              </div>
              <div className={styles.previewVitals}>
                <MiniVital
                  icon={Heart}
                  label="Heart rate"
                  value="94 bpm"
                  tone="heart"
                />
                <MiniVital
                  icon={Footprints}
                  label="Steps"
                  value="4,415"
                  tone="steps"
                />
                <MiniVital
                  icon={MoonStars}
                  label="Sleep"
                  value="7.5 hrs"
                  tone="sleep"
                />
              </div>
            </div>
          </div>
        </div>
        <div className={styles.laptopBase} />
      </div>

      <div className={styles.watch} aria-label="Illustrative round StressGuard smartwatch">
        <span className={styles.watchButton} />
        <div className={styles.watchFace}>
          <span className={styles.watchTime}>10:08</span>
          <div className={styles.watchRing}>
            <strong>45%</strong>
            <span>Normal</span>
          </div>
          <div className={styles.watchVitals}>
            <Heart size={11} weight="fill" aria-hidden />
            <Footprints size={11} weight="fill" aria-hidden />
            <MoonStars size={11} weight="fill" aria-hidden />
          </div>
        </div>
      </div>
    </div>
  );
}

type MiniVitalProps = {
  icon: typeof Heart;
  label: string;
  value: string;
  tone: "heart" | "steps" | "sleep";
};

function MiniVital({ icon: Icon, label, value, tone }: MiniVitalProps) {
  return (
    <div className={styles.miniVital} data-tone={tone}>
      <span>
        <Icon size={14} weight="fill" aria-hidden />
      </span>
      <div>
        <small>{label}</small>
        <strong>{value}</strong>
      </div>
    </div>
  );
}
