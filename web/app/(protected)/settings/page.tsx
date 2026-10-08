import { redirect } from "next/navigation";

import { PageHeader } from "@/components/layout/page-header";
import { hasExternalAuthProvider } from "@/lib/auth/providers";
import { createSupabaseServerClient } from "@/lib/supabase/server";

import { SettingsForms } from "./settings-forms";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const supabase = await createSupabaseServerClient();
  if (!supabase) redirect("/auth?reason=configuration");

  const { data: { user } } = await supabase.auth.getUser();
  if (!user) redirect("/auth?reason=session");

  const { data: profile } = await supabase
    .from("profiles")
    .select("display_name,password_set")
    .eq("id", user.id)
    .maybeSingle();

  return (
    <div className="grid gap-5">
      <PageHeader
        title="Settings"
        description="Keep your profile and account up to date, and review your data controls."
      />
      <SettingsForms
        displayName={profile?.display_name ?? ""}
        email={user.email ?? ""}
        hasPassword={profile?.password_set ?? false}
        usesGoogle={hasExternalAuthProvider(user)}
      />
    </div>
  );
}
