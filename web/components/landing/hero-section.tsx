import {
  ArrowRight,
  ChartBar,
  Heart,
  Leaf,
  Play,
} from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import { ProductPreview } from "./product-preview";
import styles from "./landing.module.css";

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
          <p className="eyebrow">Your wellness companion</p>
          <h1 className="display-title">Stress less. Understand more.</h1>
          <p className={"body-copy " + styles.heroBody}>
            Review wearable stress insights, understand your patterns, and find practical guidance
            for a calmer day.
          </p>
          <div className={styles.heroActions}>
            <Link className="button-primary" href="/auth?mode=register">
              Get started
              <ArrowRight size={17} weight="bold" aria-hidden />
            </Link>
            <Link className="button-secondary" href="/#health-preview">
              <Play size={17} weight="fill" aria-hidden />
              View demo
            </Link>
          </div>
          <div className={styles.benefits}>
            {benefits.map(({ icon: Icon, label }) => (
              <div key={label}>
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
