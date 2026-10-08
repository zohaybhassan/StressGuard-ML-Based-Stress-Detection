import {
  ArrowRight,
  ChartBar,
  GooglePlayLogo,
  Heart,
  Leaf,
} from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import { ProductPreview } from "./product-preview";
import styles from "./landing-reference.module.css";

const benefits = [
  { icon: Heart, label: "Feel better every day" },
  { icon: ChartBar, label: "Data-driven insights" },
  { icon: Leaf, label: "A calmer, healthier you" },
];

export function HeroSection() {
  return (
    <section className={styles.hero}>
      <div className={"page-container " + styles.heroGrid}>
        <div className={styles.heroCopy}>
          <div className={styles.heroHeadingGroup}>
            <p className={styles.heroEyebrow} data-hero-reveal="eyebrow">
              Your wellness companion
            </p>
            <h1 className={styles.heroTitle} aria-label="Stress Less. Understand More.">
              <span className={styles.heroTitleLine} data-hero-reveal="title-one">
                Stress Less.
              </span>
              <span className={styles.heroTitleLine} data-hero-reveal="title-two">
                Understand More.
              </span>
            </h1>
          </div>
          <p className={"body-copy " + styles.heroBody} data-hero-reveal="body">
            AI-powered stress insights from your wearable data. Track your body, understand what it
            means, and get personalized guidance for a calmer, healthier you.
          </p>
          <div className={styles.heroActions} data-hero-reveal="actions">
            <Link className="button-primary" href="/auth?mode=register">
              Get Started
              <ArrowRight size={17} weight="bold" aria-hidden />
            </Link>
            <Link
              className={"button-secondary " + styles.playStoreCta}
              href="https://play.google.com/store/search?q=StressGuard&c=apps"
              target="_blank"
              rel="noreferrer"
            >
              <GooglePlayLogo size={18} weight="fill" aria-hidden />
              Get it on Google Play
            </Link>
          </div>
          <div className={styles.benefits}>
            {benefits.map(({ icon: Icon, label }, index) => (
              <div key={label} data-hero-reveal={`benefit-${index + 1}`}>
                <span>
                  <Icon size={17} weight="duotone" aria-hidden />
                </span>
                {label}
              </div>
            ))}
          </div>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}
