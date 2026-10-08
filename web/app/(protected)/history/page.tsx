import { connection } from "next/server";
import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import { BrowserTimeZone } from "@/components/dashboard/browser-time-zone";
import { HistoryLoadingState } from "@/components/history/history-loading";
import { HistoryOverview } from "@/components/history/history-overview";
import styles from "@/components/history/history.module.css";
import { DASHBOARD_TIME_ZONE_COOKIE, getZonedRangeBounds, normalizeTimeZone } from "@/lib/dashboard/time-zone";
import { getHistoryPage } from "@/lib/history/repository";
import type { HistoryItem } from "@/lib/history/types";
import { parseHistoryFilters, type HistorySearchParams } from "@/lib/history/validation";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const fetchCache = "force-no-store";
export default async function HistoryPage({ searchParams }: { searchParams: Promise<HistorySearchParams> }) {
  await connection();
  const [params, cookieStore] = await Promise.all([searchParams, cookies()]);
  const parsed = parseHistoryFilters(params);
  if (!parsed.success) {
    return <section className={styles.configurationError} role="alert"><h1>Check your history filters</h1>
      <p>{parsed.message}</p><Link className="button-secondary" href="/history">Clear filters</Link></section>;
  }
  const timeZone = normalizeTimeZone(cookieStore.get(DASHBOARD_TIME_ZONE_COOKIE)?.value);
  if (!timeZone) return <><BrowserTimeZone /><HistoryLoadingState /></>;

  const now = new Date();
  const result = await getHistoryPage(parsed.filters, timeZone, now);
  if (result.status === "unauthorized") redirect("/auth?next=/history");
  if (result.status === "configuration-error") {
    return <div className={styles.history}><BrowserTimeZone /><section className={styles.configurationError} role="alert">
      <h1>History is not configured</h1><p>Add the public Supabase URL and anonymous key to the web environment. A private service key is not required.</p>
    </section></div>;
  }
  if (result.status === "query-error") {
    return <div className={styles.history}><BrowserTimeZone /><section className={styles.configurationError} role="alert">
      <h1>We could not load your history</h1><p>Your synchronized records remain private. Try again in a moment.</p>
      <Link className="button-secondary" href="/history">Try again</Link></section></div>;
  }

  const detail = parsed.filters.detail
    ? result.data.items.find((item: HistoryItem) => `${item.kind}:${item.id}` === parsed.filters.detail) ?? null
    : null;
  const exportBounds = getZonedRangeBounds(now, timeZone, 30);
  return <><BrowserTimeZone /><HistoryOverview data={result.data} filters={parsed.filters} timeZone={timeZone}
    detail={detail} exportRange={{ from: exportBounds.dateKeys[0], to: exportBounds.dateKeys.at(-1) as string }} /></>;
}
