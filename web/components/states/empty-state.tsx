import type { Icon } from "@phosphor-icons/react";

type EmptyStateProps = {
  icon: Icon;
  title: string;
  description: string;
};

export function EmptyState({ icon: IconComponent, title, description }: EmptyStateProps) {
  return (
    <section
      className="surface-card grid min-h-64 place-items-center p-8 text-center"
      aria-labelledby="empty-state-title"
    >
      <div className="max-w-md">
        <span className="mx-auto mb-5 grid size-12 place-items-center rounded-full bg-[var(--color-brand-soft)] text-[var(--color-brand-strong)]">
          <IconComponent size={24} weight="duotone" aria-hidden />
        </span>
        <h2 id="empty-state-title" className="text-xl font-semibold">
          {title}
        </h2>
        <p className="mt-2 text-sm leading-6 text-[var(--color-text-secondary)]">
          {description}
        </p>
      </div>
    </section>
  );
}
