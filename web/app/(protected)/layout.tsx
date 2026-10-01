import { redirect } from "next/navigation";

import { AppShell } from "@/components/layout/app-shell";
import { getPasswordGate } from "@/lib/auth/password-gate";

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

  return <AppShell email={session.user.email ?? "StressGuard account"}>{children}</AppShell>;
}
