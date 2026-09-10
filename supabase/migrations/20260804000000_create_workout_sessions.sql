-- StressGuard: workout_sessions
--
-- Manual Workout Mode sessions. These are exercise records, not stress predictions: readings
-- collected while Workout Mode is active are deliberately excluded from stress_predictions,
-- latency_metrics, alert_events and stress_feedback so gym/running heart-rate spikes do not count
-- as stress. This table keeps the workout summary separately.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.

create table if not exists public.workout_sessions (
    id                  bigint generated always as identity primary key,
    user_id             uuid not null references auth.users (id) on delete cascade,

    started_at          timestamptz not null,
    planned_end_at      timestamptz not null,
    ended_at            timestamptz,
    status              text not null check (status in ('active', 'paused', 'completed')),

    total_paused_ms     bigint not null default 0 check (total_paused_ms >= 0),
    first_steps         integer,
    last_steps          integer,
    min_heart_rate      integer,
    max_heart_rate      integer,
    heart_rate_sum      bigint not null default 0 check (heart_rate_sum >= 0),
    heart_rate_samples  integer not null default 0 check (heart_rate_samples >= 0),
    updated_at          timestamptz not null,
    created_at          timestamptz not null default now(),

    unique (user_id, started_at)
);

create index if not exists workout_sessions_user_time
    on public.workout_sessions (user_id, started_at desc);

comment on table public.workout_sessions is
    'Manual workout sessions. Uploaded separately from stress prediction history because workout '
    'readings intentionally skip stress inference and should not affect daily stress counts.';

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- Same model as the synced history tables: the app ships the publishable key, so RLS is what
-- separates one user's exercise history from another's. The sync worker upserts, so update is
-- required for idempotent retry.
-- ---------------------------------------------------------------------------

alter table public.workout_sessions enable row level security;

drop policy if exists "workout_sessions_select_own" on public.workout_sessions;
create policy "workout_sessions_select_own"
    on public.workout_sessions for select
    to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "workout_sessions_insert_own" on public.workout_sessions;
create policy "workout_sessions_insert_own"
    on public.workout_sessions for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "workout_sessions_update_own" on public.workout_sessions;
create policy "workout_sessions_update_own"
    on public.workout_sessions for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);
