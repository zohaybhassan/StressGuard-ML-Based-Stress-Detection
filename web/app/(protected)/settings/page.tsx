import { GearSix } from "@phosphor-icons/react/dist/ssr";

import { PageHeader } from "@/components/layout/page-header";
import { EmptyState } from "@/components/states/empty-state";

export const metadata = { title: "Settings" };

export default function SettingsPage() {
  return (
    <div className="grid gap-8">
      <PageHeader
        title="Settings"
        description="Manage your StressGuard profile, health checklist, privacy preferences, and account."
      />
      <EmptyState
        icon={GearSix}
        title="Settings forms are in the account phase"
        description="No profile fields are being guessed. Forms will use the exact profile and checklist constraints already defined in Supabase."
      />
    </div>
  );
}
