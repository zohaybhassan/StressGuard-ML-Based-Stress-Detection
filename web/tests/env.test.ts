import { afterEach, describe, expect, it } from "vitest";

import { getPublicSupabaseConfig } from "@/lib/validation/env";

const originalUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
const originalKey = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;

afterEach(() => {
  process.env.NEXT_PUBLIC_SUPABASE_URL = originalUrl;
  process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY = originalKey;
});

describe("getPublicSupabaseConfig", () => {
  it("returns null when public configuration is absent", () => {
    delete process.env.NEXT_PUBLIC_SUPABASE_URL;
    delete process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;

    expect(getPublicSupabaseConfig()).toBeNull();
  });

  it("accepts an HTTPS URL and publishable key", () => {
    process.env.NEXT_PUBLIC_SUPABASE_URL = "https://stressguard.supabase.co";
    process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY = "sb_publishable_test";

    expect(getPublicSupabaseConfig()).toEqual({
      url: "https://stressguard.supabase.co",
      publishableKey: "sb_publishable_test",
    });
  });

  it("rejects a non-HTTPS URL", () => {
    process.env.NEXT_PUBLIC_SUPABASE_URL = "http://stressguard.supabase.co";
    process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY = "sb_publishable_test";

    expect(getPublicSupabaseConfig()).toBeNull();
  });
});
