-- StressGuard: profiles.password_set
--
-- Records whether the account has a password, so the app knows when to ask for one.
--
-- The flow this supports: users sign up with Google, which creates a Supabase account with an
-- *empty* `encrypted_password`. Signing in with that email and any password would fail forever with
-- "Invalid login credentials", because there is no password to check against. So immediately after
-- a Google sign-up the app asks the user to set one, and from then on either route works.
--
-- Why a column rather than reading it from the session: `auth.users.encrypted_password` is not
-- exposed to the client, and inferring it from `user.identities` depends on GoTrue internals that
-- have changed between versions. A column we set ourselves is deterministic and survives a
-- reinstall, which a local flag would not.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.

alter table public.profiles
    add column if not exists password_set boolean not null default false;

comment on column public.profiles.password_set is
    'Whether this account has a password. False for a Google sign-up until the user sets one, '
    'which is what sends them to the set-password screen.';

-- ---------------------------------------------------------------------------
-- Backfill for accounts that already exist.
--
-- An empty string rather than null is what GoTrue stores for an OAuth-only user, so both have to
-- be treated as "no password".
-- ---------------------------------------------------------------------------

update public.profiles p
set password_set = true
from auth.users u
where u.id = p.id
  and u.encrypted_password is not null
  and u.encrypted_password <> '';

-- ---------------------------------------------------------------------------
-- New signups get the right value from the start.
--
-- Replaces the function from 20260725000000_create_profiles.sql; the trigger itself is unchanged
-- and still points here. Email sign-ups arrive with a bcrypt hash already set and so never see the
-- set-password screen; Google sign-ups arrive with none and do.
-- ---------------------------------------------------------------------------

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (id, email, display_name, avatar_url, password_set)
    values (
        new.id,
        new.email,
        coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name'),
        new.raw_user_meta_data ->> 'avatar_url',
        new.encrypted_password is not null and new.encrypted_password <> ''
    )
    on conflict (id) do nothing;
    return new;
end;
$$;
