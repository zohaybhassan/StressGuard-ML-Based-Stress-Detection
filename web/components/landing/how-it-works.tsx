import {
  ChartLine,
  LinkSimple,
  Sparkle,
  Watch,
} from "@phosphor-icons/react/dist/ssr";

import styles from "./landing.module.css";

const steps = [
  {
    icon: Watch,
    title: "Connect your watch",
    description: "Link your Wear OS device to the Android app and allow the required health access.",
  },
  {
    icon: ChartLine,
    title: "Track body signals",
    description: "Heart rate, steps, and available sleep data are processed through the phone.",
  },
  {
    icon: Sparkle,
    title: "Review useful context",
    description: "See synchronized patterns and guidance designed to inform, not diagnose.",
  },
];

export function HowItWorks() {
  return (
    <section id="how-it-works" className={styles.howSection}>
      <div className={"page-container " + styles.howGrid}>
        <div className={styles.howIntro}>
          <span className={styles.linkIcon}>
            <LinkSimple size={23} weight="bold" aria-hidden />
          </span>
          <h2 className="section-title">How it works</h2>
          <p className="body-copy">
            Your watch senses. Android interprets. The web portal helps you look back.
          </p>
        </div>
        <ol className={styles.steps}>
          {steps.map(({ icon: Icon, title, description }, index) => (
            <li key={title}>
              <div className={styles.stepTop}>
                <span className={styles.stepNumber}>{index + 1}</span>
                <span className={styles.stepIcon}>
                  <Icon size={24} weight="duotone" aria-hidden />
                </span>
              </div>
              <h3>{title}</h3>
              <p>{description}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
