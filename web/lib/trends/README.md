# Trends data semantics

Trends are aggregated on the server from authenticated `stress_predictions`
queries. Every query is filtered by `user_id` and remains protected by Supabase
Row Level Security. Raw prediction rows never cross the Server Component to the
chart client; only daily aggregates are serialized.

The selected range includes today and the preceding 6 or 29 local calendar
days. UTC timestamps are grouped using the browser's IANA time zone. Missing
days are represented as `null` chart points so Recharts leaves a gap instead of
drawing a zero.

Daily heart rate, sleep, and activity values use the same mean-per-prediction
semantics as Android `StressHistory.summarise`. Android rounds heart rate and
activity to whole numbers; web does the same. Stress uses persisted `stressed`
or `high_stress` labels because Supabase does not store the model manifest's
class count.

`sleep_hours`, `daily_steps`, and `activity_level` are prediction inputs rather
than independent history streams. In particular, `daily_steps` is the watch's
cumulative step snapshot at prediction time, while `activity_level` is the
full-day value supplied to the model. The activity chart therefore follows
Android and plots average `activity_level`; it does not claim to show complete
step history or sleep sessions.
