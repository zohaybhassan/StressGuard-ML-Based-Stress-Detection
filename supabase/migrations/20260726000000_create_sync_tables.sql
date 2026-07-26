-- StressGuard: the tables the background sync worker drains into.
--
-- Plan §15. The app writes everything locally first -- detection and alerting must work with no
-- network, which is the project's central claim -- and a WorkManager job uploads from Room later.
-- These tables are therefore a copy of local history, never the source of truth for inference.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.
--
-- Every table carries `unique (user_id, <event time>)`. That is what makes the upload idempotent:
-- the worker retries after a failure, and a retry that re-sends rows already accepted must not
-- duplicate them. A natural key rather than a client-generated id because Room's autoincrement
-- restarts at 1 after a reinstall, so it identifies nothing stable; two events cannot share a
-- millisecond given the watch's five-second send floor.

-- ---------------------------------------------------------------------------
-- stress_predictions
-- ---------------------------------------------------------------------------

create table if not exists public.stress_predictions (
    id            bigint generated always as identity primary key,
    user_id       uuid not null references auth.users (id) on delete cascade,

    -- When the vitals were *measured*, not when the phone received them. Background collection
    -- batches deliveries, so a sample can arrive minutes late; dating by arrival would bunch a
    -- whole batch at one instant and make any trend built from it wrong.
    recorded_at   timestamptz not null,

    label         text    not null,
    class_index   integer not null,
    confidence    real    not null,
    -- Full distribution, so a later re-analysis is not limited to the argmax.
    probabilities real[]  not null default '{}',
    -- Plan §19 requires the model build on every prediction record.
    model_version text    not null,

    heart_rate    integer not null,
    -- What the watch measured: steps since midnight.
    daily_steps   integer not null,
    -- What the model was given: a full-day activity level. These differ while the day is young;
    -- see StepHistory. Both are kept so the history can explain its own predictions.
    activity_level integer not null,
    sleep_hours   real    not null,

    -- The inputs fell outside the model's training ranges, so the trees clamped to their outermost
    -- leaf and this prediction is an extrapolation.
    out_of_training_range boolean not null default false,

    created_at    timestamptz not null default now(),

    unique (user_id, recorded_at)
);

create index if not exists stress_predictions_user_time
    on public.stress_predictions (user_id, recorded_at desc);

comment on table public.stress_predictions is
    'Uploaded copy of on-device predictions. Local Room storage is the source of truth; this is '
    'for long-term analysis and the trends screen.';

-- ---------------------------------------------------------------------------
-- latency_metrics
-- ---------------------------------------------------------------------------

create table if not exists public.latency_metrics (
    id            bigint generated always as identity primary key,
    user_id       uuid not null references auth.users (id) on delete cascade,
    recorded_at   timestamptz not null,

    preprocessing_ms          bigint not null,
    inference_ms              bigint not null,
    ui_update_ms              bigint not null,
    -- Plan §12 targets: under 1000 ms, and under 300 ms respectively.
    receive_to_prediction_ms  bigint not null,
    prediction_to_alert_ms    bigint,
    total_ms                  bigint not null,

    -- The first inference of a process loads roughly 13.8 MB of ONNX. Averaging cold starts in
    -- with steady state describes neither, so they are separated here as they are locally.
    cold_start    boolean not null default false,

    created_at    timestamptz not null default now(),

    unique (user_id, recorded_at)
);

create index if not exists latency_metrics_user_time
    on public.latency_metrics (user_id, recorded_at desc);

comment on table public.latency_metrics is
    'Measured local latency, per plan §12. Uploaded for reporting only; the alert path never '
    'waits on this table.';

-- ---------------------------------------------------------------------------
-- alert_events
-- ---------------------------------------------------------------------------

create table if not exists public.alert_events (
    id            bigint generated always as identity primary key,
    user_id       uuid not null references auth.users (id) on delete cascade,
    fired_at      timestamptz not null,

    -- Human-readable trigger, e.g. "3 of the last 5 readings indicated high stress".
    reason              text    not null,
    high_count_in_window integer not null,
    window_size         integer not null,
    model_version       text    not null,
    dismissed           boolean not null default false,

    created_at    timestamptz not null default now(),

    unique (user_id, fired_at)
);

create index if not exists alert_events_user_time
    on public.alert_events (user_id, fired_at desc);

comment on table public.alert_events is
    'Alerts that actually fired, after smoothing and cooldown. Plan §13.';

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- The publishable key shipped in the app is public by design, so RLS is the only thing separating
-- one user's data from another's. Policies key off auth.uid(), which comes from the verified JWT
-- and cannot be spoofed by the client.
--
-- Deliberately no delete policy on any of these, matching public.profiles: history is removed by
-- deleting the account, which cascades from auth.users. The app's own retention policy prunes the
-- local copy and does not need to reach the server.
-- ---------------------------------------------------------------------------

alter table public.stress_predictions enable row level security;
alter table public.latency_metrics    enable row level security;
alter table public.alert_events       enable row level security;

do $$
declare
    t text;
begin
    foreach t in array array['stress_predictions', 'latency_metrics', 'alert_events'] loop
        execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
        execute format(
            'create policy %I on public.%I for select to authenticated using ((select auth.uid()) = user_id)',
            t || '_select_own', t
        );

        execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
        execute format(
            'create policy %I on public.%I for insert to authenticated with check ((select auth.uid()) = user_id)',
            t || '_insert_own', t
        );

        -- Update is needed because the worker upserts: a retry after a partial failure sends rows
        -- the server may already hold, and without update permission the conflict resolution is
        -- rejected rather than ignored.
        execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
        execute format(
            'create policy %I on public.%I for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)',
            t || '_update_own', t
        );
    end loop;
end $$;
