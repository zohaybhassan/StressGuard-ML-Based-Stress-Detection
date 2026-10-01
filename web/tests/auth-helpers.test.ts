import { afterEach, describe, expect, it } from "vitest";

import { friendlyAuthError } from "@/lib/auth/errors";
import { authMode, callbackUrl, safeNextPath } from "@/lib/auth/redirects";

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
