import Image from "next/image";
import Link from "next/link";

import styles from "./landing.module.css";

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={"page-container " + styles.footerGrid}>
        <div className={styles.footerBrand}>
          <Image
            src="/brand/stressguard-logo.png"
            alt="StressGuard"
            width={463}
            height={408}
          />
          <p>Understand your body. Build a calmer day.</p>
        </div>
        <nav className={styles.footerNav} aria-label="Footer navigation">
          <Link href="/#features">Features</Link>
          <Link href="/#how-it-works">How it works</Link>
          <Link href="/#health-preview">Insights</Link>
          <Link href="/auth">Sign in</Link>
        </nav>
        <div className={styles.footerNote}>
          <strong>Important</strong>
          <p>
            StressGuard provides wellness insights and supportive guidance. It does not diagnose
            medical or mental health conditions.
          </p>
        </div>
      </div>
      <div className={"page-container " + styles.footerBottom}>
        <span>StressGuard final-year project</span>
        <span>Privacy-first companion portal</span>
      </div>
    </footer>
  );
}
