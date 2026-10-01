import { Watch } from "@phosphor-icons/react/dist/ssr";

import { PageHeader } from "@/components/layout/page-header";
import { EmptyState } from "@/components/states/empty-state";

export const metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <div className="grid gap-8">
      <PageHeader
        title="Dashboard"
        description="Your latest synchronized StressGuard reading and daily overview will appear here."
      />
      <EmptyState
        icon={Watch}
        title="Waiting for synchronized data"
        description="Wearable sensing and stress alerts run through the Android and Wear OS apps. This portal will display their synchronized results."
      />
    </div>
  );
}
