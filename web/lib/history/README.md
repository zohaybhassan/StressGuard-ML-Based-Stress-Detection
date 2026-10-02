# History data semantics

History reads the existing Supabase event tables with the authenticated server
client. Every query includes the current `user_id` in addition to the database's
Row Level Security policy. Responses are dynamic and uncached.

Predictions and workouts use direct PostgREST range pagination with an exact
count. Alerts and feedback are independent tables without a foreign key. Their
tab creates a chronological timeline from bounded server-side slices of each
table; the records are not presented as a formal database join. The page size is
20 and page input is capped at 100 to bound work.

Date filters are interpreted as local calendar dates using the browser IANA
time-zone cookie, then converted to an inclusive UTC start and exclusive UTC end.
Predictions filter on `recorded_at`, alerts on `fired_at`, feedback on
`responded_at`, and workouts on `started_at`.

Raw latency metrics are intentionally excluded. Feedback profile snapshots and
probability JSON are also omitted from the normal History interface because they
are unnecessary for personal review.
