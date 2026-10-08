"use client";

import { ArrowRight, List, X } from "@phosphor-icons/react";
import Link from "next/link";
import { useRef } from "react";

import styles from "./public-navigation.module.css";

type NavigationItem = { href: string; label: string };

export function MobilePublicNavigation({ items }: { items: readonly NavigationItem[] }) {
  const menuRef = useRef<HTMLDetailsElement>(null);

  function closeMenu() {
    if (menuRef.current) menuRef.current.open = false;
  }

  return (
    <details className={styles.mobileMenu} ref={menuRef}>
      <summary aria-label="Menu">
        <List className={styles.openIcon} size={25} aria-hidden />
        <X className={styles.closeIcon} size={25} aria-hidden />
      </summary>
      <nav className={styles.mobilePanel} aria-label="Mobile navigation">
        {items.map((item) => (
          <Link key={item.href} href={item.href} onClick={closeMenu}>
            {item.label}
          </Link>
        ))}
        <Link href="/auth" onClick={closeMenu}>Sign in</Link>
        <Link className={styles.mobileCta} href="/auth?mode=register" onClick={closeMenu}>
          Get Started
          <ArrowRight size={16} weight="bold" aria-hidden />
        </Link>
      </nav>
    </details>
  );
}
