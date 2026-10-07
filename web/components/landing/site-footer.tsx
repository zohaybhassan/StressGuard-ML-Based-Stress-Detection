import Link from "next/link";
import { InstagramLogo, LinkedinLogo, TwitterLogo, YoutubeLogo } from "@phosphor-icons/react/dist/ssr";

import { BrandLockup } from "@/components/brand/brand-lockup";

import styles from "./landing-reference.module.css";

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={"page-container " + styles.footerGrid}>
        <div className={styles.footerBrand}>
          <BrandLockup />
          <p>Understand your body. A calmer, healthier you.</p>
        </div>
        <nav className={styles.footerNav} aria-label="Footer navigation">
          <Link href="/#features">Features</Link>
          <Link href="/#how-it-works">How It Works</Link>
          <Link href="/#health-preview">Trends</Link>
          <Link href="/#assistant">Assistant</Link>
          <span>Privacy</span>
          <span>Terms</span>
          <span>Support</span>
        </nav>
        <div className={styles.footerSocial} aria-label="StressGuard social channels">
          <span aria-label="Twitter"><TwitterLogo size={17} weight="fill" /></span>
          <span aria-label="Instagram"><InstagramLogo size={17} weight="bold" /></span>
          <span aria-label="YouTube"><YoutubeLogo size={17} weight="fill" /></span>
          <span aria-label="LinkedIn"><LinkedinLogo size={17} weight="fill" /></span>
          <small>© 2026 StressGuard. All rights reserved.</small>
        </div>
      </div>
    </footer>
  );
}
