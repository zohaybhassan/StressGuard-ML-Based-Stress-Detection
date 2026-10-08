"use client";

import {
  ArrowClockwise,
  ChartLineUp,
  ClockCounterClockwise,
  GearSix,
  House,
} from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { BrandLockup } from "@/components/brand/brand-lockup";
import { SignOutButton } from "@/components/auth/sign-out-button";

import styles from "./app-shell.module.css";

const navigation = [
  { href: "/dashboard", label: "Dashboard", icon: House },
  { href: "/trends", label: "Trends", icon: ChartLineUp },
  { href: "/history", label: "History", icon: ClockCounterClockwise },
  { href: "/settings", label: "Settings", icon: GearSix },
];

type AppShellProps = {
  children: React.ReactNode;
  email: string;
  displayName: string | null;
};

function ReloadNotice({ mobile = false }: { mobile?: boolean }) {
  return (
    <div className={`${styles.reloadCard} ${mobile ? styles.mobileReload : styles.sidebarReload}`} role="note" tabIndex={0}>
      <span className={styles.reloadIcon} aria-hidden="true">
        <ArrowClockwise size={15} weight="bold" />
      </span>
      <div>
        <strong>Updates on reload</strong>
        <p>New app readings appear here when you reload this page.</p>
      </div>
    </div>
  );
}

export function AppShell({ children, email, displayName }: AppShellProps) {
  const pathname = usePathname();
  const name = displayName?.trim() || email.split("@")[0] || "Your account";
  const initial = name.slice(0, 1).toUpperCase();
  const [accountOpen, setAccountOpen] = useState(false);
  const accountMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!accountOpen) return;
    const dismiss = (event: PointerEvent) => {
      if (!accountMenuRef.current?.contains(event.target as Node)) setAccountOpen(false);
    };
    const dismissEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setAccountOpen(false);
    };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", dismissEscape);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", dismissEscape);
    };
  }, [accountOpen]);

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <BrandLockup href={null} />
        <nav className={styles.sideNav} aria-label="Portal navigation">
          {navigation.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                className={active ? styles.activeLink : styles.sideLink}
                href={item.href}
                aria-current={active ? "page" : undefined}
              >
                <Icon size={21} weight={active ? "fill" : "regular"} aria-hidden />
                {item.label}
              </Link>
            );
          })}
        </nav>
        <ReloadNotice />
      </aside>

      <div className={styles.contentColumn}>
        <header className={styles.topbar}>
          <BrandLockup href={null} />
          <div className={styles.account}>
            <div className={styles.accountMenu} ref={accountMenuRef}>
              <button
                aria-expanded={accountOpen}
                aria-haspopup="true"
                aria-label={`Account menu for ${name}`}
                className={styles.avatar}
                onClick={() => setAccountOpen((current) => !current)}
                type="button"
              >
                {initial}
              </button>
              {accountOpen ? (
                <div aria-label="Account options" className={styles.accountDropdown} role="group">
                  <span className={styles.dropdownEyebrow}>Signed in as</span>
                  <strong>{name}</strong>
                  <small>{email}</small>
                  <Link href="/settings" onClick={() => setAccountOpen(false)}>
                    <GearSix size={18} aria-hidden />
                    Settings
                  </Link>
                </div>
              ) : null}
            </div>
            <SignOutButton />
          </div>
        </header>
        <ReloadNotice mobile />
        <main className={styles.main}>{children}</main>
      </div>

      <nav className={styles.mobileNav} aria-label="Mobile portal navigation">
        {navigation.map((item) => {
          const Icon = item.icon;
          const active = pathname === item.href;
          return (
            <Link
              key={item.href}
              className={active ? styles.mobileActive : undefined}
              href={item.href}
              aria-current={active ? "page" : undefined}
            >
              <Icon size={21} weight={active ? "fill" : "regular"} aria-hidden />
              <span>{item.label}</span>
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
