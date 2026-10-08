# History reporting

History reads the authenticated user's existing Supabase rows under RLS. The
web client never sends a user ID. Filters use the browser's IANA timezone and
convert inclusive local dates to an exclusive UTC end boundary, including DST
transitions.

## CSV schema

The export is a single chronological table. `record_type` is one of
`prediction`, `alert`, `feedback`, or `workout`. Fields that do not apply to a
record are blank—not zero. The columns are defined once in
`lib/reports/types.ts` and mirrored by `infra/aws/lambda/report_csv.py`.
Profile snapshots, probability arrays, database IDs, user IDs, and email
addresses are deliberately excluded.

Spreadsheet formula prefixes (`=`, `+`, `-`, and `@`) are escaped by the Lambda
CSV writer.

## Request boundary

1. The browser POSTs only `from`, `to`, and `timeZone` to `/api/reports`.
2. Next.js validates the same-origin request and the Supabase user server-side.
3. RLS-scoped queries gather at most 5,000 rows across at most 90 inclusive days.
4. The server sends normalized rows, an opaque HMAC subject hash, and a random
   request ID to a private Lambda invocation.
5. Lambda derives the S3 object key, writes an AES-256 encrypted private object,
   and returns a ten-minute presigned GET URL.
6. S3 lifecycle configuration deletes objects after one day.

No health records are logged by either application component. The endpoint is
uncached and returns generic errors.

## Rate limiting

The app limits a subject to three export attempts per 15-minute window using an
HMAC key and a warm-instance in-memory window. This provides immediate abuse
protection without changing the existing database. Because Vercel can run
multiple instances, production should additionally enforce a distributed
limit at the edge/WAF or swap this adapter for a managed atomic store before a
high-volume launch. Lambda reserved concurrency and payload/row/range limits
provide independent cost and workload bounds.

## Environments

Deploy a separate SAM stack for development, staging, and production. Configure
`AWS_REGION`, `AWS_REPORT_FUNCTION_NAME`, optional
`AWS_REPORT_FUNCTION_QUALIFIER`, and `REPORT_RATE_LIMIT_SALT` as server-only
Vercel variables. The Lambda's bucket setting is managed by CloudFormation.

Without AWS configuration, browsing and filtering History still works; export
returns a friendly unavailable state. Tests do not require live AWS credentials.
