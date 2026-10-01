"use client";

import {
  ChartLineUp,
  ClockCounterClockwise,
  GearSix,
  House,
} from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";

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
};

export function AppShell({ children, email }: AppShellProps) {
  const pathname = usePathname();
  const initial = email.slice(0, 1).toUpperCase();

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <BrandLockup />
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
        <div className={styles.syncNote}>
          <strong>Android connected</strong>
          <span>Portal data is synchronized from the mobile app.</span>
        </div>
      </aside>

      <div className={styles.contentColumn}>
        <header className={styles.topbar}>
          <BrandLockup compact />
          <div className={styles.account}>
            <div className={styles.accountText}>
              <span>Signed in</span>
              <strong>{email}</strong>
            </div>
            <span className={styles.avatar} aria-hidden>
              {initial}
            </span>
            <SignOutButton />
          </div>
        </header>
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
