import {
  ArrowRight,
  ChartBar,
  Heart,
  Leaf,
  Play,
} from "@phosphor-icons/react/dist/ssr";
import Image from "next/image";
import Link from "next/link";

import styles from "./landing.module.css";

export function FinalCta() {
  return (
    <section id="about" className={styles.finalSection}>
      <div className={"page-container " + styles.finalPanel}>
        <div className={styles.finalImage}>
          <Image
            src="/brand/wellness-journey.jpg"
            alt="A hiker pausing beside a mountain lake"
            fill
            sizes="(max-width: 800px) 100vw, 46vw"
          />
        </div>
        <div className={styles.finalCopy}>
          <p className="eyebrow">A calmer, healthier you</p>
          <h2 className="section-title">Start your wellness journey today</h2>
          <p className="body-copy">
            Bring your Android and Wear OS insights into one thoughtful place built for reflection.
          </p>
          <div className={styles.finalActions}>
            <Link className="button-primary" href="/auth?mode=register">
              Get started
              <ArrowRight size={17} weight="bold" aria-hidden />
            </Link>
            <Link className="button-secondary" href="/#health-preview">
              <Play size={17} weight="fill" aria-hidden />
              View demo
            </Link>
          </div>
          <div className={styles.finalBenefits}>
            <span>
              <Leaf size={18} weight="duotone" aria-hidden />
              Less stress, more clarity
            </span>
            <span>
              <Heart size={18} weight="duotone" aria-hidden />
              Better-informed daily choices
            </span>
            <span>
              <ChartBar size={18} weight="duotone" aria-hidden />
              Small insights, meaningful patterns
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}
