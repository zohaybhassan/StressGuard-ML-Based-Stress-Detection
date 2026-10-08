import { redirect } from "next/navigation";

import { AppShell } from "@/components/layout/app-shell";
import { getPasswordGate } from "@/lib/auth/password-gate";
import { createSupabaseServerClient } from "@/lib/supabase/server";

export const dynamic = "force-dynamic";

export default async function ProtectedLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const session = await getPasswordGate();

  if (!session.configured) {
    redirect("/auth?reason=configuration");
  }

  if (!session.user) {
    redirect("/auth?reason=session");
  }

  if (!session.passwordSet) {
    redirect("/auth?mode=set-password");
  }

  const supabase = await createSupabaseServerClient();
  const { data: profile } = supabase
    ? await supabase.from("profiles").select("display_name").eq("id", session.user.id).maybeSingle()
    : { data: null };

  return (
    <AppShell email={session.user.email ?? "StressGuard account"} displayName={profile?.display_name ?? null}>
      {children}
    </AppShell>
  );
}
