import { z } from "zod";
import { normalizeTimeZone } from "@/lib/dashboard/time-zone";
import { REPORT_MAX_RANGE_DAYS } from "./types";

const dateKey = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/)
  .refine((value) => {
    const [year, month, day] = value.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day));
    return (
      date.getUTCFullYear() === year &&
      date.getUTCMonth() + 1 === month &&
      date.getUTCDate() === day
    );
  }, "Invalid calendar date");

export const reportRequestSchema = z
  .object({
    from: dateKey,
    to: dateKey,
    timeZone: z.string().min(1).max(80).refine((value) => normalizeTimeZone(value) !== null),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.from > value.to) {
      context.addIssue({
        code: "custom",
        path: ["to"],
        message: "End date must not precede start date",
      });
      return;
    }

    const rangeDays =
      Math.floor(
        (Date.parse(`${value.to}T00:00:00.000Z`) -
          Date.parse(`${value.from}T00:00:00.000Z`)) /
          86_400_000,
      ) + 1;
    if (rangeDays > REPORT_MAX_RANGE_DAYS) {
      context.addIssue({
        code: "custom",
        path: ["to"],
        message: `Reports are limited to ${REPORT_MAX_RANGE_DAYS} days`,
      });
    }
  });

export type ReportRequest = z.infer<typeof reportRequestSchema>;

export function reportErrorResponse(code: string) {
  const messages: Record<string, string> = {
    unauthorized: "Sign in again before requesting a report.",
    invalid_request: `Choose a valid date range of ${REPORT_MAX_RANGE_DAYS} days or less.`,
    rate_limited: "Too many reports were requested. Wait a few minutes and try again.",
    too_large: "This range contains too many records. Choose a shorter date range.",
    export_unavailable: "Report export is not configured in this environment.",
    generation_failed: "The report could not be generated. Try again later.",
  };
  return messages[code] ?? messages.generation_failed;
}
