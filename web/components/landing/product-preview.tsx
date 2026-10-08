import Image from "next/image";
import {
  ArrowDownLeft,
  ArrowDownRight,
  Barbell,
  CalendarBlank,
  ChartLineUp,
  ChatCircleText,
  Footprints,
  Gear,
  Heart,
  House,
  MoonStars,
} from "@phosphor-icons/react/dist/ssr";

import styles from "./landing-reference.module.css";

export function ProductPreview() {
  return (
    <div className={styles.productStage} data-hero-reveal="product" aria-hidden="true">
      <span className={styles.watchNote}>
        Works with<br />your wearable
        <ArrowDownRight size={22} weight="bold" aria-hidden />
      </span>
      <span className={styles.insightNote}>
        Real insights<br />for a calmer you
        <ArrowDownLeft size={22} weight="bold" aria-hidden />
      </span>
      <div className={styles.laptop} aria-label="Illustrative StressGuard dashboard preview">
        <div className={styles.laptopCamera} />
        <div className={styles.laptopScreen}>
          <aside className={styles.previewSidebar}>
            <span className={styles.previewBrand}>
              <Image
                className={styles.previewBrandMark}
                src="/brand/stressguard-mark.png"
                alt=""
                width={16}
                height={16}
              />
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
            <span>
              <ChatCircleText size={13} aria-hidden />
              Assistant
            </span>
            <span>
              <Barbell size={13} aria-hidden />
              Workout
            </span>
            <span>
              <Gear size={13} aria-hidden />
              Settings
            </span>
          </aside>
          <div className={styles.previewMain}>
            <div className={styles.previewWelcome}>
              <span>Welcome back</span>
              <strong>Ethan Carter</strong>
              <small><CalendarBlank size={10} aria-hidden /> Mon, Aug 5, 2024</small>
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
                <small>Watch data live &nbsp;|&nbsp; last alert: 3m ago</small>
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
            <div className={styles.assistantPrompt}>
              <ChatCircleText size={13} weight="fill" aria-hidden />
              <span>Feeling overwhelmed?</span>
              <strong>Talk through it with the assistant</strong>
            </div>
          </div>
        </div>
        <div className={styles.laptopHinge} aria-hidden />
        <div className={styles.laptopDeck} aria-hidden>
          <span className={styles.laptopKeyboard} />
          <span className={styles.laptopTrackpad} />
        </div>
        <div className={styles.laptopBase} />
        <span className={styles.laptopContactShadow} aria-hidden />
      </div>

      <div className={styles.watch} aria-label="Illustrative round StressGuard smartwatch">
        <Image
          className={styles.watchBody}
          src="/brand/hero-smartwatch-v5-three-quarter.png"
          alt=""
          fill
          priority
          sizes="(max-width: 620px) 9rem, (max-width: 1120px) 12rem, 15rem"
        />
        <div className={styles.watchFace}>
          <span className={styles.watchTime}>10:08</span>
          <div className={styles.watchRing}>
            <strong>45%</strong>
            <span>Normal</span>
          </div>
          <div className={styles.watchVitals}>
            <span><Heart size={11} weight="fill" aria-hidden />94</span>
            <span><Footprints size={11} weight="fill" aria-hidden />4,974</span>
            <span><MoonStars size={11} weight="fill" aria-hidden />7.5h</span>
          </div>
        </div>
        <span className={styles.watchContactShadow} aria-hidden />
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
