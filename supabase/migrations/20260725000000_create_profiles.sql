-- StressGuard: profiles
--
-- One row per authenticated user, holding the values the stress model consumes. Keeping them
-- server-side means a profile survives reinstalling the app, and it is the anchor every other
-- user-owned table will reference.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.

create table if not exists public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,

    -- Mirrored from the identity provider, for display only.
    email         text,
    display_name  text,
    avatar_url    text,

    -- Model inputs. Constraints match what the model was trained on, so the database
    -- rejects a profile the model could only extrapolate from. Ranges and category names
    -- come from ml_engine/data/sleep_health_dataset.csv; see ml_engine/prepare_dataset.py.
    age           integer check (age between 18 and 80),
    gender        text    check (gender in ('Male', 'Female')),
    occupation    text    check (occupation in (
                      'Accountant', 'Artist', 'Chef', 'Doctor', 'Engineer', 'Lawyer',
                      'Manager', 'Nurse', 'Sales Representative', 'Salesperson',
                      'Scientist', 'Software Engineer', 'Student', 'Teacher', 'Writer')),
    bmi_category  text    check (bmi_category in ('Normal', 'Overweight', 'Obese', 'Underweight')),

    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

comment on table public.profiles is
    'App-level profile for each auth.users row. Model input columns are constrained to the '
    'training data''s ranges and category names.';

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- Without this every authenticated user could read and modify every other user''s row: the
-- publishable key shipped in the app is public by design, so RLS is the only thing separating
-- one user''s data from another''s.
--
-- Policies key off auth.uid(), which comes from the verified JWT and cannot be spoofed by the
-- client. Never authorise against a column the user can write.
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own"
    on public.profiles for select
    to authenticated
    using ((select auth.uid()) = id);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own"
    on public.profiles for insert
    to authenticated
    with check ((select auth.uid()) = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own"
    on public.profiles for update
    to authenticated
    using ((select auth.uid()) = id)
    with check ((select auth.uid()) = id);

-- Deliberately no delete policy. Removing a profile should go through deleting the account,
-- which cascades from auth.users.

-- ---------------------------------------------------------------------------
-- Create a profile automatically on signup, so the app never has to handle the
-- "authenticated but no row yet" case.
-- ---------------------------------------------------------------------------

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (id, email, display_name, avatar_url)
    values (
        new.id,
        new.email,
        coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name'),
        new.raw_user_meta_data ->> 'avatar_url'
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- Keep updated_at honest.
-- ---------------------------------------------------------------------------

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function public.touch_updated_at();
