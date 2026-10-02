export function formatDateTime(value: string | null, timeZone: string) {
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

export function formatStressLabel(value: string) {
  return value
    .trim()
    .replaceAll("_", " ")
    .replaceAll("-", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function formatDuration(milliseconds: number | null) {
  if (milliseconds === null) {
    return "Not available";
  }

  const totalMinutes = Math.round(milliseconds / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;

  if (hours === 0) {
    return `${minutes} min`;
  }

  return minutes === 0 ? `${hours} hr` : `${hours} hr ${minutes} min`;
}

export function getGreeting(date: Date, timeZone: string) {
  const hourPart = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour: "numeric",
    hourCycle: "h23",
  })
    .formatToParts(date)
    .find((part) => part.type === "hour")?.value;
  const hour = Number(hourPart);

  if (hour < 12) {
    return "Good morning";
  }

  if (hour < 18) {
    return "Good afternoon";
  }

  return "Good evening";
}
