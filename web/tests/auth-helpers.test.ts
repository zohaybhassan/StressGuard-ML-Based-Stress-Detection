import { afterEach, describe, expect, it } from "vitest";

import { friendlyAuthError } from "@/lib/auth/errors";
import { hasExternalAuthProvider } from "@/lib/auth/providers";
import {
  authMode,
  callbackErrorKind,
  callbackUrl,
  safeNextPath,
} from "@/lib/auth/redirects";
import { isSupabaseAuthCookie } from "@/lib/auth/session-cookies";

const originalSiteUrl = process.env.NEXT_PUBLIC_SITE_URL;
const originalVercelUrl = process.env.VERCEL_URL;

afterEach(() => {
  process.env.NEXT_PUBLIC_SITE_URL = originalSiteUrl;
  process.env.VERCEL_URL = originalVercelUrl;
});

describe("auth redirect helpers", () => {
  it("allows local paths and rejects protocol-relative or external paths", () => {
    expect(safeNextPath("/history?page=2")).toBe("/history?page=2");
    expect(safeNextPath("//attacker.example/path")).toBe("/dashboard");
    expect(safeNextPath("https://attacker.example/path")).toBe("/dashboard");
  });

  it("normalizes unknown auth modes to sign in", () => {
    expect(authMode("register")).toBe("register");
    expect(authMode("unexpected")).toBe("sign-in");
  });

  it("builds callbacks from the configured canonical site", () => {
    process.env.NEXT_PUBLIC_SITE_URL = "https://stressguard.example/";
    expect(callbackUrl("/auth?mode=reset")).toBe(
      "https://stressguard.example/auth/callback?next=%2Fauth%3Fmode%3Dreset",
    );
  });
});

describe("friendly auth errors", () => {
  it("does not expose raw backend details", () => {
    expect(friendlyAuthError({ message: "database connection internals" })).toBe(
      "We could not complete that request. Please try again.",
    );
  });

  it("keeps invalid-login feedback generic", () => {
    expect(friendlyAuthError({ code: "invalid_credentials" })).toBe(
      "Email or password is incorrect.",
    );
  });
});

describe("authentication providers", () => {
  it("accepts Google as a complete sign-in method without a local password", () => {
    expect(
      hasExternalAuthProvider({
        app_metadata: { provider: "google", providers: ["google"] },
        identities: [],
      }),
    ).toBe(true);
  });

  it("still requires password setup for an email-only identity", () => {
    expect(
      hasExternalAuthProvider({
        app_metadata: { provider: "email", providers: ["email"] },
        identities: [],
      }),
    ).toBe(false);
  });

  it("recognizes a linked external identity even when email is primary", () => {
    expect(
      hasExternalAuthProvider({
        app_metadata: { provider: "email", providers: ["email", "google"] },
        identities: [],
      }),
    ).toBe(true);
  });

  it("distinguishes expired email links from OAuth failures", () => {
    expect(callbackErrorKind("otp_expired", "/auth?status=verified")).toBe(
      "expired-link",
    );
    expect(callbackErrorKind(null, "/auth?mode=reset")).toBe("expired-link");
    expect(callbackErrorKind("access_denied", "/dashboard")).toBe("oauth");
  });
});

describe("authentication cookies", () => {
  it("recognizes Supabase session cookies and their chunks", () => {
    expect(isSupabaseAuthCookie("sb-projectref-auth-token")).toBe(true);
    expect(isSupabaseAuthCookie("sb-projectref-auth-token.0")).toBe(true);
    expect(isSupabaseAuthCookie("sb-projectref-auth-token-code-verifier")).toBe(true);
  });

  it("does not classify unrelated application cookies as auth sessions", () => {
    expect(isSupabaseAuthCookie("stressguard-time-zone")).toBe(false);
    expect(isSupabaseAuthCookie("analytics-session")).toBe(false);
  });
});
