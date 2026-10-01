export function PageSkeleton() {
  return (
    <div className="grid gap-5" aria-label="Loading page" aria-busy="true">
      <div className="h-9 w-56 animate-pulse rounded-lg bg-[var(--color-surface-muted)]" />
      <div className="h-5 w-full max-w-xl animate-pulse rounded bg-[var(--color-surface-muted)]" />
      <div className="mt-5 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <div
            key={index}
            className="h-40 animate-pulse rounded-[var(--radius-card)] border border-[var(--color-border)] bg-[var(--color-surface)]"
          />
        ))}
      </div>
    </div>
  );
}
