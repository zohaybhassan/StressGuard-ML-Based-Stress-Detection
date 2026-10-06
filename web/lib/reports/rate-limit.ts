import "server-only";

import { createHmac, randomBytes } from "node:crypto";

const WINDOW_MS = 15 * 60 * 1_000;
const MAX_REQUESTS = 3;
const processSalt = randomBytes(32).toString("hex");
const attempts = new Map<string, { count: number; resetAt: number }>();

export type RateLimitResult =
  | { allowed: true }
  | { allowed: false; retryAfterSeconds: number };

export function privateSubjectHash(userId: string) {
  const salt = process.env.REPORT_RATE_LIMIT_SALT || processSalt;
  return createHmac("sha256", salt).update(userId).digest("hex");
}

export function checkReportRateLimit(userId: string, now = Date.now()): RateLimitResult {
  const key = privateSubjectHash(userId);
  const current = attempts.get(key);
  if (!current || current.resetAt <= now) {
    attempts.set(key, { count: 1, resetAt: now + WINDOW_MS });
    return { allowed: true };
  }
  if (current.count >= MAX_REQUESTS) {
    return {
      allowed: false,
      retryAfterSeconds: Math.max(1, Math.ceil((current.resetAt - now) / 1_000)),
    };
  }
  current.count += 1;
  return { allowed: true };
}

export function resetReportRateLimitForTests() {
  attempts.clear();
}
