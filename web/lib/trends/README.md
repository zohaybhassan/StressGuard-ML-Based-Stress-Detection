# Trends data semantics

Trends are aggregated on the server from authenticated `stress_predictions`
queries. Every query is filtered by `user_id` and remains protected by Supabase
Row Level Security. Raw prediction rows never cross the Server Component to the
chart client; only daily aggregates are serialized.

The selected range includes today and the preceding 6 or 29 local calendar
days. UTC timestamps are grouped using the browser's IANA time zone. Missing
days are represented as `null` chart points so Recharts leaves a gap instead of
drawing a zero.

The four web charts show daily stressed-reading proportion, average heart rate,
average sleep-hours input, and average activity input. Each chart requires at
least two days with its measurement to draw a trend; a single observed day is
still shown in the summary and daily-value table. The web offers both seven-
and 30-day views.

Daily heart rate, sleep, and activity values use the same mean-per-prediction
semantics as Android `StressHistory.summarise`. Android rounds heart rate and
activity to whole numbers; web does the same. Stress uses persisted `stressed`
or `high_stress` labels because Supabase does not store the model manifest's
class count. Only synchronized cloud readings can appear on web; an unsynced
local Android reading cannot appear until upload succeeds.

`sleep_hours`, `daily_steps`, and `activity_level` are prediction inputs rather
than independent history streams. In particular, `daily_steps` is the watch's
cumulative step snapshot at prediction time, while `activity_level` is the
full-day value supplied to the model. The activity chart therefore follows
Android and plots average `activity_level`; it does not claim to show complete
step history or sleep sessions.
