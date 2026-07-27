-- StressGuard: health_checklists
--
-- Plan §6 and §16. The self-reported risk factors the rule-based recommendation in plan §7 scores
-- alongside the stress history. Nothing here is measured -- it is what the user told us -- which is
-- precisely why §7 uses it to suggest a routine checkup rather than to conclude anything medical.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.
--
-- One row per user, not an event stream. Unlike stress_predictions and its siblings, this table
-- holds the user's *current* answers: the score asks whether they smoke, not when they said so. So
-- the primary key is the user id itself and a re-save is an upsert onto the same row, rather than
-- the `unique (user_id, <event time>)` natural key the history tables use for idempotent retries.

create table if not exists public.health_checklists (
    user_id       uuid primary key references auth.users (id) on delete cascade,

    -- The eight factors plan §7 assigns points to. Defaulted false so a partially filled form is
    -- still a valid row: the score treats "not reported" and "no" identically by design, since the
    -- alternative is refusing to produce any recommendation until every box has been considered.
    smoking             boolean not null default false,
    heart_condition     boolean not null default false,
    hypertension        boolean not null default false,
    diabetes            boolean not null default false,
    sleep_disorder      boolean not null default false,
    anxiety_history     boolean not null default false,
    high_caffeine_use   boolean not null default false,
    physically_inactive boolean not null default false,

    -- When the user last answered. The recommendation quotes this, because a risk score resting on
    -- year-old self-report should not be presented with the same confidence as a fresh one.
    updated_at    timestamptz not null default now(),
    created_at    timestamptz not null default now()
);

comment on table public.health_checklists is
    'Self-reported health risk factors, one row per user. Input to the rule-based recommendation '
    'in plan §7. Self-report, not measurement.';

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- Same reasoning as public.profiles: the publishable key shipped in the app is public by design,
-- so RLS is the only thing separating one user's data from another's. This table is the most
-- sensitive in the project -- it is a list of medical conditions -- so the policies are not
-- optional and there is deliberately no policy granting access to anyone else.
--
-- No delete policy, matching the other tables: removing this data goes through deleting the
-- account, which cascades from auth.users.
-- ---------------------------------------------------------------------------

alter table public.health_checklists enable row level security;

drop policy if exists "health_checklists_select_own" on public.health_checklists;
create policy "health_checklists_select_own"
    on public.health_checklists for select
    to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "health_checklists_insert_own" on public.health_checklists;
create policy "health_checklists_insert_own"
    on public.health_checklists for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

-- Update is needed because the app upserts: editing the checklist re-sends the whole row against
-- the existing primary key, and without update permission that conflict is rejected.
drop policy if exists "health_checklists_update_own" on public.health_checklists;
create policy "health_checklists_update_own"
    on public.health_checklists for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------------
-- Keep updated_at honest, reusing the trigger function public.profiles already defines.
-- ---------------------------------------------------------------------------

drop trigger if exists health_checklists_touch_updated_at on public.health_checklists;
create trigger health_checklists_touch_updated_at
    before update on public.health_checklists
    for each row execute function public.touch_updated_at();
