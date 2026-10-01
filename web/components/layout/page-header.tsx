import type { ReactNode } from "react";

type PageHeaderProps = {
  title: string;
  description: string;
  action?: ReactNode;
};

export function PageHeader({ title, description, action }: PageHeaderProps) {
  return (
    <header className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] md:text-4xl">
          {title}
        </h1>
        <p className="mt-2 max-w-2xl text-[var(--color-text-secondary)]">
          {description}
        </p>
      </div>
      {action}
    </header>
  );
}
