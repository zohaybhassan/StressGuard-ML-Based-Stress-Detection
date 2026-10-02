import { describe, expect, it } from "vitest";
import { decodeLambdaResponse } from "@/lib/aws/report-response";

describe("report Lambda adapter", () => {
  it("accepts the narrow HTTPS response contract", () => {
    const result = decodeLambdaResponse(new TextEncoder().encode(JSON.stringify({ download_url: "https://private.example.test/report.csv?signature=test", expires_at: "2026-10-02T10:10:00.000Z" })));
    expect(result.downloadUrl).toContain("https://private.example.test");
  });

  it("rejects malformed or insecure responses", () => {
    expect(() => decodeLambdaResponse(new TextEncoder().encode('{"download_url":"http://example.test"}'))).toThrow();
  });
});
