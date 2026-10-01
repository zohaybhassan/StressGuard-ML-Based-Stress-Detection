import {
  ArrowRight,
  Barbell,
  ChartLineUp,
  ChatCircleText,
  Heart,
  Pulse,
} from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import styles from "./landing.module.css";

const features = [
  {
    icon: Pulse,
    title: "On-device monitoring",
    description: "Wear OS vitals feed private, on-device stress inference through Android.",
    tone: "mint",
    href: "/#how-it-works",
  },
  {
    icon: Heart,
    title: "Daily vitals",
    description: "Heart rate, steps, sleep, and stress context stay together in one clear view.",
    tone: "coral",
    href: "/#health-preview",
  },
  {
    icon: ChatCircleText,
    title: "Supportive guidance",
    description: "The Android assistant offers grounded support when you choose to start a chat.",
    tone: "lavender",
    href: "/#about",
  },
  {
    icon: ChartLineUp,
    title: "Trends and insights",
    description: "Compare patterns over time without presenting the result as a diagnosis.",
    tone: "blue",
    href: "/auth",
  },
  {
    icon: Barbell,
    title: "Workout mode",
    description: "Exercise sessions stay separate so elevated workout heart rate is not misread.",
    tone: "sky",
    href: "/#about",
  },
];

export function FeatureGrid() {
  return (
    <section id="features" className="section-space">
      <div className="page-container">
        <div className={styles.sectionIntro}>
          <h2 className="section-title">Built around your real day</h2>
          <p className="body-copy">
            StressGuard connects sensing, private inference, clear trends, and practical support.
          </p>
        </div>
        <div className={styles.featureGrid}>
          {features.map(({ icon: Icon, title, description, tone, href }, index) => (
            <article key={title} className={styles.featureCard} data-tone={tone} data-index={index}>
              <span className={styles.featureIcon}>
                <Icon size={24} weight="duotone" aria-hidden />
              </span>
              <h3>{title}</h3>
              <p>{description}</p>
              <Link href={href}>
                Learn more
                <ArrowRight size={15} weight="bold" aria-hidden />
              </Link>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
