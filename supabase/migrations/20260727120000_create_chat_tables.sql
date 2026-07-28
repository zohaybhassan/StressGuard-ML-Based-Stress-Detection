-- StressGuard: supportive chatbot storage, plan §6 and §18.
--
-- Full transcripts are kept, which is the more exposed of the two options §18 offers. That is a
-- deliberate choice and it carries obligations: these rows hold a user describing their mental
-- state, so the RLS below is not a formality, and `on delete cascade` from auth.users is what
-- makes "delete my account" actually delete the conversation.
--
-- Run in the Supabase SQL editor, or via `supabase db push` with the CLI.

create table if not exists public.chat_sessions (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references auth.users (id) on delete cascade,

    started_at    timestamptz not null default now(),
    -- Null while the conversation is still open.
    ended_at      timestamptz,

    -- What the app thought of the user when the conversation began. The point of the feature is
    -- support during high stress, so being able to show that sessions cluster around high-stress
    -- predictions is the evidence that it is used when intended.
    stress_at_start text,

    -- True if any message in this session tripped the crisis check. Stored on the session rather
    -- than derived, so the safety behaviour can be audited without reading anyone's messages.
    crisis_fallback_fired boolean not null default false,

    created_at    timestamptz not null default now()
);

create index if not exists chat_sessions_user_time
    on public.chat_sessions (user_id, started_at desc);

comment on table public.chat_sessions is
    'One supportive-chat conversation. Metadata only; the messages are in chat_messages.';

create table if not exists public.chat_messages (
    id            bigint generated always as identity primary key,
    session_id    uuid not null references public.chat_sessions (id) on delete cascade,
    -- Denormalised from the session so RLS can be enforced on this table without a join.
    user_id       uuid not null references auth.users (id) on delete cascade,

    -- 'user' or 'assistant'. Constrained rather than free text: the column drives how a message is
    -- rendered, and an unexpected value would silently render as the wrong speaker.
    role          text not null check (role in ('user', 'assistant')),
    content       text not null,

    -- True when this reply came from the safety fallback rather than the model. Without it a
    -- transcript cannot show whether the crisis path was exercised, which is exactly what §18's
    -- testing asks to demonstrate.
    is_fallback   boolean not null default false,

    created_at    timestamptz not null default now(),

    unique (session_id, created_at)
);

create index if not exists chat_messages_session_time
    on public.chat_messages (session_id, created_at);

comment on table public.chat_messages is
    'Full chat transcript. Contains user-authored descriptions of their mental state; treat as '
    'sensitive and never expose outside the owning user''s RLS scope.';

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- The publishable key shipped in the app is public by design, so RLS is the only thing separating
-- one user's conversation from another's. Policies key off auth.uid(), which comes from the
-- verified JWT and cannot be spoofed by the client.
--
-- Unlike the history tables, delete IS allowed here. A user who wants a conversation about their
-- mental health gone should not have to delete their whole account to get it.
-- ---------------------------------------------------------------------------

alter table public.chat_sessions enable row level security;
alter table public.chat_messages enable row level security;

do $$
declare
    t text;
begin
    foreach t in array array['chat_sessions', 'chat_messages'] loop
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

        execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
        execute format(
            'create policy %I on public.%I for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)',
            t || '_update_own', t
        );

        execute format('drop policy if exists %I on public.%I', t || '_delete_own', t);
        execute format(
            'create policy %I on public.%I for delete to authenticated using ((select auth.uid()) = user_id)',
            t || '_delete_own', t
        );
    end loop;
end $$;
