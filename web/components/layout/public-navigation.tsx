import { ArrowRight, List, X } from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import { BrandLockup } from "@/components/brand/brand-lockup";

import styles from "./public-navigation.module.css";

const navigation = [
  { href: "/#features", label: "Features" },
  { href: "/#how-it-works", label: "How It Works" },
  { href: "/#health-preview", label: "Trends" },
  { href: "/#assistant", label: "Assistant" },
  { href: "/#about", label: "About" },
];

export function PublicNavigation() {
  return (
    <header className={styles.header}>
      <div className={"page-container " + styles.inner}>
        <BrandLockup prominent splitTone />
        <nav className={styles.desktopNav} aria-label="Main navigation">
          {navigation.map((item) => (
            <Link key={item.href} href={item.href}>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className={styles.desktopActions}>
          <Link className="button-quiet" href="/auth">
            Sign in
          </Link>
          <Link className="button-primary" href="/auth?mode=register">
            Get Started
            <ArrowRight size={16} weight="bold" aria-hidden />
          </Link>
        </div>
        <details className={styles.mobileMenu}>
          <summary aria-label="Open navigation">
            <List className={styles.openIcon} size={25} aria-hidden />
            <X className={styles.closeIcon} size={25} aria-hidden />
          </summary>
          <nav className={styles.mobilePanel} aria-label="Mobile navigation">
            {navigation.map((item) => (
              <Link key={item.href} href={item.href}>
                {item.label}
              </Link>
            ))}
            <Link href="/auth">Sign in</Link>
            <Link className={styles.mobileCta} href="/auth?mode=register">
              Get Started
              <ArrowRight size={16} weight="bold" aria-hidden />
            </Link>
          </nav>
        </details>
      </div>
    </header>
  );
}
