import { ChartLineUp } from "@phosphor-icons/react/dist/ssr";

import { PageHeader } from "@/components/layout/page-header";
import { EmptyState } from "@/components/states/empty-state";

export const metadata = { title: "Trends" };

export default function TrendsPage() {
  return (
    <div className="grid gap-8">
      <PageHeader
        title="Trends"
        description="Review stress, heart rate, sleep, and activity patterns across 7 or 30 days."
      />
      <EmptyState
        icon={ChartLineUp}
        title="Trend charts are in the next data phase"
        description="This route is protected and ready for server-side aggregation from your Supabase prediction history."
      />
    </div>
  );
}
