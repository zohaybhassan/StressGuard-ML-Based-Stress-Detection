"use client";

import { createBrowserClient } from "@supabase/ssr";

import { getPublicSupabaseConfig } from "@/lib/validation/env";
import type { Database } from "@/types/database";

export function createSupabaseBrowserClient() {
  const config = getPublicSupabaseConfig();
  if (!config) {
    throw new Error("Supabase public configuration is missing or invalid.");
  }

  return createBrowserClient<Database>(config.url, config.publishableKey);
}
