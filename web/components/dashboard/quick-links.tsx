import Link from "next/link";
import {
  ArrowUpRight,
  ChartLineUp,
  GearSix,
  ListBullets,
} from "@phosphor-icons/react/dist/ssr";
import styles from "./dashboard.module.css";

const links = [
  {
    href: "/trends",
    title: "Explore trends",
    description: "Review patterns across stress, heart rate, activity, and sleep.",
    icon: ChartLineUp,
  },
  {
    href: "/history",
    title: "Open history",
    description: "Browse synchronized predictions, alerts, and workouts.",
    icon: ListBullets,
  },
  {
    href: "/settings",
    title: "Manage settings",
    description: "Edit your profile and password, and review your data controls.",
    icon: GearSix,
  },
] as const;

export function QuickLinks() {
  return (
    <nav className={styles.quickLinks} aria-label="Dashboard shortcuts">
      {links.map((item) => {
        const Icon = item.icon;
        return (
          <Link key={item.href} href={item.href}>
            <span className={styles.quickIcon} aria-hidden="true">
              <Icon size={21} weight="duotone" />
            </span>
            <span>
              <strong>{item.title}</strong>
              <small>{item.description}</small>
            </span>
            <ArrowUpRight className={styles.quickArrow} size={18} aria-hidden="true" />
          </Link>
        );
      })}
    </nav>
  );
}
