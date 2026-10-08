import { ArrowRight } from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import { BrandLockup } from "@/components/brand/brand-lockup";
import { MobilePublicNavigation } from "./mobile-public-navigation";

import styles from "./public-navigation.module.css";

const navigation = [
  { href: "/#home", label: "Home" },
  { href: "/#features", label: "Features" },
  { href: "/#how-it-works", label: "How It Works" },
  { href: "/#health-preview", label: "Trends" },
  { href: "/#about", label: "About" },
];

export function PublicNavigation() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <BrandLockup href={null} />
        <nav className={styles.desktopNav} aria-label="Main navigation">
          {navigation.map((item) => (
            <Link key={item.href} href={item.href}>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className={styles.desktopActions}>
          <Link className={`button-quiet ${styles.signIn}`} href="/auth">
            Sign in
          </Link>
          <Link className={`button-primary ${styles.getStarted}`} href="/auth?mode=register">
            Get Started
            <ArrowRight size={16} weight="bold" aria-hidden />
          </Link>
        </div>
        <MobilePublicNavigation items={navigation} />
      </div>
    </header>
  );
}
