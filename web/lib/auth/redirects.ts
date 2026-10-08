export type AuthMode = "sign-in" | "register" | "forgot" | "reset" | "set-password";

const SAFE_AUTH_MODES = new Set<AuthMode>([
  "sign-in",
  "register",
  "forgot",
  "reset",
  "set-password",
]);

export function safeNextPath(value: string | null | undefined, fallback = "/dashboard") {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return fallback;

  try {
    const parsed = new URL(value, "https://stressguard.local");
    return parsed.origin === "https://stressguard.local"
      ? `${parsed.pathname}${parsed.search}${parsed.hash}`
      : fallback;
  } catch {
    return fallback;
  }
}

export function authMode(value: string | undefined): AuthMode {
  return value && SAFE_AUTH_MODES.has(value as AuthMode) ? (value as AuthMode) : "sign-in";
}

export function getSiteUrl() {
  const configured = process.env.NEXT_PUBLIC_SITE_URL?.trim();
  if (configured) return configured.replace(/\/$/, "");

  const vercelUrl = process.env.VERCEL_URL?.trim();
  if (vercelUrl) return `https://${vercelUrl.replace(/\/$/, "")}`;

  return "http://localhost:3000";
}

export function callbackUrl(nextPath: string) {
  const callback = new URL("/auth/callback", getSiteUrl());
  callback.searchParams.set("next", safeNextPath(nextPath));
  return callback.toString();
}

export function callbackErrorKind(errorCode: string | null, nextPath: string | null) {
  const next = safeNextPath(nextPath, "/auth");
  const isEmailLink =
    errorCode === "otp_expired" ||
    next.startsWith("/auth?mode=reset") ||
    next.startsWith("/auth?status=verified");

  return isEmailLink ? "expired-link" : "oauth";
}
