import { randomUUID } from "node:crypto";
import { NextResponse, type NextRequest } from "next/server";
import { getReportLambdaConfig, invokeReportLambda } from "@/lib/aws/report-invoker";
import { getZonedDateRangeBounds } from "@/lib/dashboard/time-zone";
import { normalizeReportRows } from "@/lib/reports/normalization";
import { checkReportRateLimit, privateSubjectHash } from "@/lib/reports/rate-limit";
import { ReportTooLargeError } from "@/lib/reports/limits";
import { getReportData } from "@/lib/reports/repository";
import { REPORT_MAX_PAYLOAD_BYTES } from "@/lib/reports/types";
import { reportRequestSchema } from "@/lib/reports/validation";
import { createSupabaseServerClient } from "@/lib/supabase/server";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export const runtime = "nodejs";

function json(body: unknown, status: number, headers?: HeadersInit) {
  return NextResponse.json(body, {
    status,
    headers: { "Cache-Control": "private, no-store, max-age=0", ...headers },
  });
}

export function isSameOrigin(request: Pick<NextRequest, "headers" | "nextUrl">) {
  const origin = request.headers.get("origin");
  if (!origin) return true;
  const forwardedHost = request.headers.get("x-forwarded-host");
  const host = forwardedHost ?? request.headers.get("host");
  if (!host) return false;
  try {
    return new URL(origin).host === host && new URL(origin).protocol === request.nextUrl.protocol;
  } catch {
    return false;
  }
}

export async function POST(request: NextRequest) {
  if (!isSameOrigin(request)) return json({ code: "invalid_request" }, 403);

  const client = await createSupabaseServerClient();
  if (!client) return json({ code: "export_unavailable" }, 503);
  const { data: { user } } = await client.auth.getUser();
  if (!user) return json({ code: "unauthorized" }, 401);

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return json({ code: "invalid_request" }, 400);
  }
  const parsed = reportRequestSchema.safeParse(body);
  if (!parsed.success) return json({ code: "invalid_request" }, 400);

  const lambdaConfig = getReportLambdaConfig();
  if (!lambdaConfig) return json({ code: "export_unavailable" }, 503);
  const rateLimit = checkReportRateLimit(user.id);
  if (!rateLimit.allowed) {
    return json(
      { code: "rate_limited" },
      429,
      { "Retry-After": String(rateLimit.retryAfterSeconds) },
    );
  }

  try {
    const { from, to, timeZone } = parsed.data;
    const bounds = getZonedDateRangeBounds(from, to, timeZone);
    if (!bounds.start || !bounds.end) return json({ code: "invalid_request" }, 400);
    const reportData = await getReportData(client, user.id, bounds.start, bounds.end);
    const rows = normalizeReportRows(reportData, timeZone, new Date());
    const payload = {
      requestId: randomUUID(),
      subjectHash: privateSubjectHash(user.id),
      fromDate: from,
      toDate: to,
      timeZone,
      rows,
    };
    const encoded = JSON.stringify(payload);
    if (Buffer.byteLength(encoded, "utf8") > REPORT_MAX_PAYLOAD_BYTES) {
      return json({ code: "too_large" }, 413);
    }
    const download = await invokeReportLambda(payload, lambdaConfig);
    return json(download, 200);
  } catch (error) {
    if (error instanceof ReportTooLargeError) return json({ code: "too_large" }, 413);
    return json({ code: "generation_failed" }, 502);
  }
}
