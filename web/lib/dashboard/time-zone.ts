export const DASHBOARD_TIME_ZONE_COOKIE = "stressguard-time-zone";
export const DEFAULT_TIME_ZONE = "UTC";

type ZonedDateParts = {
  year: number;
  month: number;
  day: number;
};

function shiftCalendarDate(parts: ZonedDateParts, days: number): ZonedDateParts {
  const shifted = new Date(Date.UTC(parts.year, parts.month - 1, parts.day + days));
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
  };
}

function dateKey(parts: ZonedDateParts) {
  return [
    String(parts.year).padStart(4, "0"),
    String(parts.month).padStart(2, "0"),
    String(parts.day).padStart(2, "0"),
  ].join("-");
}

function parseDateKey(value: string): ZonedDateParts | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;

  const parts = {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
  };
  const roundTrip = new Date(Date.UTC(parts.year, parts.month - 1, parts.day));

  return roundTrip.getUTCFullYear() === parts.year &&
    roundTrip.getUTCMonth() + 1 === parts.month &&
    roundTrip.getUTCDate() === parts.day
    ? parts
    : null;
}

function readNumericPart(
  parts: Intl.DateTimeFormatPart[],
  type: Intl.DateTimeFormatPartTypes,
) {
  const value = parts.find((part) => part.type === type)?.value;
  return value ? Number(value) : Number.NaN;
}

export function normalizeTimeZone(value: string | null | undefined) {
  if (!value || value.length > 80) {
    return null;
  }

  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return value;
  } catch {
    return null;
  }
}

export function getZonedDateParts(date: Date, timeZone: string): ZonedDateParts {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);

  return {
    year: readNumericPart(parts, "year"),
    month: readNumericPart(parts, "month"),
    day: readNumericPart(parts, "day"),
  };
}

function getTimeZoneOffset(instant: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(instant);

  const representedAsUtc = Date.UTC(
    readNumericPart(parts, "year"),
    readNumericPart(parts, "month") - 1,
    readNumericPart(parts, "day"),
    readNumericPart(parts, "hour"),
    readNumericPart(parts, "minute"),
    readNumericPart(parts, "second"),
  );

  const roundedInstant = Math.floor(instant.getTime() / 1000) * 1000;
  return representedAsUtc - roundedInstant;
}

function localMidnightToUtc(parts: ZonedDateParts, timeZone: string) {
  const targetLocalTime = Date.UTC(parts.year, parts.month - 1, parts.day);
  let candidate = targetLocalTime;

  // Iteration handles offset changes around daylight-saving transitions.
  for (let index = 0; index < 3; index += 1) {
    candidate = targetLocalTime - getTimeZoneOffset(new Date(candidate), timeZone);
  }

  return new Date(candidate);
}

export function getZonedDayBounds(date: Date, timeZone: string) {
  const currentDay = getZonedDateParts(date, timeZone);
  const nextDay = shiftCalendarDate(currentDay, 1);

  return {
    start: localMidnightToUtc(currentDay, timeZone),
    end: localMidnightToUtc(nextDay, timeZone),
    dateKey: dateKey(currentDay),
  };
}

export function getZonedRangeBounds(
  date: Date,
  timeZone: string,
  days: number,
) {
  if (!Number.isInteger(days) || days < 1) {
    throw new RangeError("days must be a positive integer");
  }

  const currentDay = getZonedDateParts(date, timeZone);
  const firstDay = shiftCalendarDate(currentDay, -(days - 1));
  const endDay = shiftCalendarDate(currentDay, 1);
  const dateKeys = Array.from({ length: days }, (_, index) =>
    dateKey(shiftCalendarDate(firstDay, index)),
  );

  return {
    start: localMidnightToUtc(firstDay, timeZone),
    end: localMidnightToUtc(endDay, timeZone),
    currentDateKey: dateKey(currentDay),
    dateKeys,
  };
}

export function getZonedDateRangeBounds(
  fromDate: string | null,
  toDate: string | null,
  timeZone: string,
) {
  const fromParts = fromDate ? parseDateKey(fromDate) : null;
  const toParts = toDate ? parseDateKey(toDate) : null;

  if ((fromDate && !fromParts) || (toDate && !toParts)) {
    throw new RangeError("Invalid calendar date");
  }

  if (fromParts && toParts && dateKey(fromParts) > dateKey(toParts)) {
    throw new RangeError("Start date must not be after end date");
  }

  return {
    start: fromParts ? localMidnightToUtc(fromParts, timeZone) : null,
    end: toParts
      ? localMidnightToUtc(shiftCalendarDate(toParts, 1), timeZone)
      : null,
  };
}
