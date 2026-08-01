-- Human ground truth collected after a real sustained-stress alert.
-- This table is deliberately separate from model predictions: predictions are machine output;
-- these rows are labels suitable for later evaluation and retraining.

create table if not exists public.stress_feedback (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    prompt_source text not null check (prompt_source in ('high_stress_alert', 'periodic_check_in')),
    alert_fired_at timestamptz not null,
    prediction_recorded_at timestamptz not null,
    responded_at timestamptz not null,
    predicted_label text not null,
    predicted_class_index integer not null,
    confidence real not null check (confidence between 0 and 1),
    probabilities jsonb not null,
    model_version text not null,
    heart_rate integer not null,
    daily_steps integer not null,
    activity_level integer not null,
    sleep_hours real not null,
    out_of_training_range boolean not null,
    profile_age integer not null,
    profile_gender text not null,
    profile_occupation text not null,
    profile_bmi text not null,
    confirmed_stressed boolean not null,
    severity integer check (
        (confirmed_stressed and severity between 1 and 10)
        or (not confirmed_stressed and severity is null)
    ),
    created_at timestamptz not null default now(),
    unique (user_id, alert_fired_at)
);

comment on table public.stress_feedback is
    'User-confirmed labels and immutable alert-time feature snapshots for offline model evaluation and future retraining.';

create index if not exists stress_feedback_user_responded
    on public.stress_feedback (user_id, responded_at desc);

alter table public.stress_feedback enable row level security;

drop policy if exists "stress_feedback_select_own" on public.stress_feedback;
create policy "stress_feedback_select_own"
    on public.stress_feedback for select
    using (auth.uid() = user_id);

drop policy if exists "stress_feedback_insert_own" on public.stress_feedback;
create policy "stress_feedback_insert_own"
    on public.stress_feedback for insert
    with check (auth.uid() = user_id);

drop policy if exists "stress_feedback_update_own" on public.stress_feedback;
create policy "stress_feedback_update_own"
    on public.stress_feedback for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
