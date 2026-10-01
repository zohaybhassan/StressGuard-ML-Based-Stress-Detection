import { ClockCounterClockwise } from "@phosphor-icons/react/dist/ssr";

import { PageHeader } from "@/components/layout/page-header";
import { EmptyState } from "@/components/states/empty-state";

export const metadata = { title: "History" };

export default function HistoryPage() {
  return (
    <div className="grid gap-8">
      <PageHeader
        title="History"
        description="Predictions, alerts, feedback, and workouts will be organized here without exposing raw private data."
      />
      <EmptyState
        icon={ClockCounterClockwise}
        title="History data is not connected yet"
        description="The typed database contract is ready. Pagination, filters, record details, and private exports follow in the History phase."
      />
    </div>
  );
}
