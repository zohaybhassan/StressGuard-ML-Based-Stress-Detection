export function formatTrendDateTime(value: string | null, timeZone: string) {
  if (!value) {
    return "Not available";
  }

  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    return "Not available";
  }

  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(date);
}

export function formatPercent(value: number | null) {
  return value === null ? "Not enough data" : `${Math.round(value * 100)}%`;
}

export function formatMetric(
  value: number | null,
  unit: "bpm" | "hours" | "activity",
) {
  if (value === null) {
    return "Not available";
  }

  if (unit === "bpm") {
    return `${Math.round(value)} bpm`;
  }

  if (unit === "hours") {
    return `${value.toFixed(1)} hr`;
  }

  return Math.round(value).toLocaleString("en-US");
}
