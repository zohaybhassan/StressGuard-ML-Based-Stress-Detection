import { z } from "zod";
import type { ReportDownload } from "@/lib/reports/types";

const responseSchema = z.object({
  download_url: z.url().refine((value) => value.startsWith("https://")),
  expires_at: z.iso.datetime(),
});

export function decodeLambdaResponse(payload: Uint8Array | undefined): ReportDownload {
  if (!payload) throw new Error("Empty report response");
  const decoded = JSON.parse(new TextDecoder().decode(payload)) as unknown;
  const result = responseSchema.safeParse(decoded);
  if (!result.success) throw new Error("Invalid report response");
  return { downloadUrl: result.data.download_url, expiresAt: result.data.expires_at };
}
