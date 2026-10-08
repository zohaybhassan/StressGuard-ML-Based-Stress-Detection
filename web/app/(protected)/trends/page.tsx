import { connection } from "next/server";
import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";

import { BrowserTimeZone } from "@/components/dashboard/browser-time-zone";
import { TrendsLoadingState } from "@/components/trends/trends-loading";
import { TrendsOverview } from "@/components/trends/trends-overview";
import styles from "@/components/trends/trends.module.css";
import {
  DASHBOARD_TIME_ZONE_COOKIE,
  normalizeTimeZone,
} from "@/lib/dashboard/time-zone";
import { parseTrendRange } from "@/lib/trends/aggregation";
import { getTrendsDataset } from "@/lib/trends/repository";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const fetchCache = "force-no-store";

export const metadata = { title: "Trends" };

type TrendsPageProps = {
  searchParams: Promise<{ range?: string | string[] }>;
};

export default async function TrendsPage({ searchParams }: TrendsPageProps) {
  await connection();

  const [{ range: rangeValue }, cookieStore] = await Promise.all([
    searchParams,
    cookies(),
  ]);
  const range = parseTrendRange(rangeValue);
  const timeZone = normalizeTimeZone(
    cookieStore.get(DASHBOARD_TIME_ZONE_COOKIE)?.value,
  );

  if (!timeZone) {
    return (
      <>
        <BrowserTimeZone />
        <TrendsLoadingState />
      </>
    );
  }

  const now = new Date();
  const result = await getTrendsDataset(range, timeZone, now);

  if (result.status === "unauthorized") {
    redirect("/auth?next=/trends");
  }

  if (result.status === "configuration-error") {
    return (
      <div className={styles.trends}>
        <BrowserTimeZone />
        <section className={styles.configurationError} role="alert">
          <h1>Trends are not configured</h1>
          <p>
            Add the public Supabase URL and anonymous key to the web environment,
            then reload this page. No private service key is required.
          </p>
        </section>
      </div>
    );
  }

  if (result.status === "query-error") {
    return (
      <div className={styles.trends}>
        <BrowserTimeZone />
        <section className={styles.configurationError} role="alert">
          <h1>We could not load your trends</h1>
          <p>
            Your synchronized health data remains private. Try the request again,
            or return later if the problem continues.
          </p>
          <Link className="button-secondary" href={`/trends?range=${range}`}>
            Try again
          </Link>
        </section>
      </div>
    );
  }

  return (
    <>
      <BrowserTimeZone />
      <TrendsOverview dataset={result.dataset} now={now} />
    </>
  );
}
