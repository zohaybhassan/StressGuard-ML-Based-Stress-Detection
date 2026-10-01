import "server-only";

import type { User } from "@supabase/supabase-js";

import { createSupabaseServerClient } from "@/lib/supabase/server";

export type CurrentUserResult =
  | { configured: false; user: null }
  | { configured: true; user: User | null };

export async function getCurrentUser(): Promise<CurrentUserResult> {
  const supabase = await createSupabaseServerClient();
  if (!supabase) return { configured: false, user: null };

  const {
    data: { user },
    error,
  } = await supabase.auth.getUser();

  if (error) return { configured: true, user: null };
  return { configured: true, user };
}
