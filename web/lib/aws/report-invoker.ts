import "server-only";

import { InvokeCommand, LambdaClient } from "@aws-sdk/client-lambda";
import { z } from "zod";
import type { ReportLambdaPayload } from "@/lib/reports/types";
import { decodeLambdaResponse } from "./report-response";

const configSchema = z.object({
  region: z.string().min(1),
  functionName: z.string().min(1),
  qualifier: z.string().min(1).optional(),
});

export function getReportLambdaConfig() {
  const parsed = configSchema.safeParse({
    region: process.env.AWS_REGION,
    functionName: process.env.AWS_REPORT_FUNCTION_NAME,
    qualifier: process.env.AWS_REPORT_FUNCTION_QUALIFIER || undefined,
  });
  return parsed.success ? parsed.data : null;
}

export async function invokeReportLambda(
  payload: ReportLambdaPayload,
  config: NonNullable<ReturnType<typeof getReportLambdaConfig>>,
  client = new LambdaClient({ region: config.region }),
) {
  const response = await client.send(
    new InvokeCommand({
      FunctionName: config.functionName,
      Qualifier: config.qualifier,
      InvocationType: "RequestResponse",
      Payload: new TextEncoder().encode(JSON.stringify(payload)),
    }),
  );
  if (response.FunctionError || (response.StatusCode ?? 500) >= 300) {
    throw new Error("Report function failed");
  }
  return decodeLambdaResponse(response.Payload);
}
