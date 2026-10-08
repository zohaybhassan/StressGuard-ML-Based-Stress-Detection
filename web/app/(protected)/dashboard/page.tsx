import { connection } from "next/server";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { BrowserTimeZone } from "@/components/dashboard/browser-time-zone";
import { DashboardOverview } from "@/components/dashboard/dashboard-overview";
import { DashboardLoadingState } from "@/components/dashboard/dashboard-loading";
import { getDashboardSnapshot } from "@/lib/dashboard/repository";
import {
  DASHBOARD_TIME_ZONE_COOKIE,
  normalizeTimeZone,
} from "@/lib/dashboard/time-zone";
import styles from "@/components/dashboard/dashboard.module.css";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const fetchCache = "force-no-store";

export default async function DashboardPage() {
  await connection();

  const cookieStore = await cookies();
  const timeZone = normalizeTimeZone(
    cookieStore.get(DASHBOARD_TIME_ZONE_COOKIE)?.value,
  );

  if (!timeZone) {
    return (
      <>
        <BrowserTimeZone />
        <DashboardLoadingState />
      </>
    );
  }

  const now = new Date();
  const result = await getDashboardSnapshot(timeZone, now);

  if (result.status === "unauthorized") {
    redirect("/auth?next=/dashboard");
  }

  if (result.status === "configuration-error") {
    return (
      <div className={styles.dashboard}>
        <BrowserTimeZone />
        <section className={styles.configurationError} role="alert">
          <h1>Dashboard is not configured</h1>
          <p>
            Add the public Supabase URL and anonymous key to the web environment,
            then reload this page. No private service key is required.
          </p>
        </section>
      </div>
    );
  }

  if (result.status === "ready") {
    return (
      <>
        <BrowserTimeZone />
        <DashboardOverview
          snapshot={result.snapshot}
          now={now}
          timeZone={timeZone}
        />
      </>
    );
  }

  return null;
}
