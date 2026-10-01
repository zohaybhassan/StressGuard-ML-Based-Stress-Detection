import "server-only";

import type { User } from "@supabase/supabase-js";

import { createSupabaseServerClient } from "@/lib/supabase/server";

export type PasswordGateResult =
  | { configured: false; user: null; passwordSet: true }
  | { configured: true; user: null; passwordSet: true }
  | { configured: true; user: User; passwordSet: boolean };

export async function getPasswordGate(): Promise<PasswordGateResult> {
  const supabase = await createSupabaseServerClient();
  if (!supabase) return { configured: false, user: null, passwordSet: true };

  const {
    data: { user },
    error: userError,
  } = await supabase.auth.getUser();

  if (userError || !user) return { configured: true, user: null, passwordSet: true };

  const { data, error } = await supabase
    .from("profiles")
    .select("password_set")
    .eq("id", user.id)
    .maybeSingle();

  // Match Android's compatibility behavior: if an older project has not applied the
  // password_set migration, do not lock every existing user out of the portal.
  if (error || !data) return { configured: true, user, passwordSet: true };

  return { configured: true, user, passwordSet: data.password_set };
}
