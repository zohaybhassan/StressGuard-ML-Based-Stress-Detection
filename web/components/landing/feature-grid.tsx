import {
  Barbell,
  ChartLineUp,
  ChatCircleText,
  Heart,
  Pulse,
  Wind,
} from "@phosphor-icons/react/dist/ssr";

import styles from "./landing-reference.module.css";

const features = [
  {
    icon: Pulse,
    title: "Live Stress Tracking",
    description: "Real-time stress monitoring from your wearable with AI-powered insights.",
    tone: "mint",
  },
  {
    icon: Heart,
    title: "Daily Vitals",
    description: "Track heart rate, sleep, steps and more, all in one place.",
    tone: "coral",
  },
  {
    icon: ChatCircleText,
    title: "AI Wellness Assistant",
    description: "Get personalized guidance and talk through how you are feeling.",
    tone: "lavender",
  },
  {
    icon: ChartLineUp,
    title: "Trends & Insights",
    description: "Discover patterns and understand what affects your stress and health.",
    tone: "blue",
  },
  {
    icon: Barbell,
    title: "Workout Mode",
    description: "Track exercise while stress predictions pause for more accurate insights.",
    tone: "sky",
  },
  {
    icon: Wind,
    title: "Breathing Exercise",
    description: "Follow calming breathing sessions designed to help you reset and relax.",
    tone: "sage",
  },
];

export function FeatureGrid() {
  return (
    <section id="features" className={styles.featuresSection}>
      <div className={"page-container " + styles.featuresContent}>
        <header className={styles.featuresIntro}>
          <h2>Everything You Need for Daily Wellness</h2>
        </header>
        <div className={styles.featureGrid}>
          {features.map(({ icon: Icon, title, description, tone }, index) => (
            <article
              key={title}
              id={title === "AI Wellness Assistant" ? "assistant" : undefined}
              className={styles.featureCard}
              data-tone={tone}
              data-index={index}
            >
              <span className={styles.featureIcon}>
                <Icon size={24} weight="duotone" aria-hidden />
              </span>
              <h3>{title}</h3>
              <p>{description}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
