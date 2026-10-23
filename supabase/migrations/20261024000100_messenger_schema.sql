-- Messenger Pro production schema for Supabase PostgreSQL.
-- All client-visible tables have RLS enabled. Writes for message delivery,
-- receipts, reactions and events go through SECURITY DEFINER functions that
-- check auth.uid() and are exposed to the authenticated role only.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null,
  username_lower text generated always as (lower(username)) stored,
  display_name text not null,
  created_at timestamptz not null default now(),
  status text not null default 'ACTIVE',
  phone text,
  email text,
  bio text
);
create unique index if not exists profiles_username_lower_key
  on public.profiles (username_lower);

create table if not exists public.contacts (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  contact_id uuid not null references public.profiles(id) on delete cascade,
  nickname text,
  note text,
  favourite boolean not null default false,
  blocked boolean not null default false,
  hidden boolean not null default false,
  ringtone text,
  updated_at timestamptz not null default now(),
  primary key (owner_id, contact_id),
  constraint contacts_not_self check (owner_id <> contact_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  client_id text not null check (char_length(client_id) between 1 and 100),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  body text not null check (char_length(body) <= 8000 and (deleted or char_length(body) >= 1)),
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted boolean not null default false,
  reply_to uuid references public.messages(id) on delete set null,
  expires_at timestamptz,
  transcript text,
  seq bigint generated always as identity,
  unique (sender_id, client_id),
  constraint messages_not_self check (sender_id <> recipient_id)
);
create index if not exists messages_seq_idx on public.messages (seq);
create index if not exists messages_recipient_idx on public.messages (recipient_id, seq);
create index if not exists messages_sender_idx on public.messages (sender_id, seq);

create table if not exists public.receipts (
  message_id uuid not null references public.messages(id) on delete cascade,
  account_id uuid not null references public.profiles(id) on delete cascade,
  state text not null check (state in ('DELIVERED', 'READ')),
  at timestamptz not null default now(),
  primary key (message_id, account_id)
);

create table if not exists public.reactions (
  message_id uuid not null references public.messages(id) on delete cascade,
  account_id uuid not null references public.profiles(id) on delete cascade,
  emoji text not null check (char_length(emoji) between 1 and 16),
  at timestamptz not null default now(),
  primary key (message_id, account_id)
);

create table if not exists public.presence (
  account_id uuid primary key references public.profiles(id) on delete cascade,
  state text not null check (state in ('ONLINE', 'OFFLINE')),
  last_seen timestamptz not null default now(),
  availability text not null default 'Available'
);

create table if not exists public.events (
  seq bigint generated always as identity primary key,
  account_id uuid not null references public.profiles(id) on delete cascade,
  type text not null,
  payload jsonb not null,
  at timestamptz not null default now()
);
create index if not exists events_account_seq_idx on public.events (account_id, seq);

alter table public.profiles enable row level security;
alter table public.contacts enable row level security;
alter table public.messages enable row level security;
alter table public.receipts enable row level security;
alter table public.reactions enable row level security;
alter table public.presence enable row level security;
alter table public.events enable row level security;

drop policy if exists profiles_read on public.profiles;
create policy profiles_read on public.profiles for select to authenticated using (true);

drop policy if exists contacts_read on public.contacts;
create policy contacts_read on public.contacts for select to authenticated
  using (owner_id = auth.uid());

drop policy if exists messages_visible on public.messages;
create policy messages_visible on public.messages for select to authenticated
  using (sender_id = auth.uid() or recipient_id = auth.uid());

drop policy if exists receipts_visible on public.receipts;
create policy receipts_visible on public.receipts for select to authenticated
  using (
    account_id = auth.uid()
    or exists (select 1 from public.messages m where m.id = message_id and m.sender_id = auth.uid())
  );

drop policy if exists reactions_visible on public.reactions;
create policy reactions_visible on public.reactions for select to authenticated
  using (exists (
    select 1 from public.messages m
    where m.id = message_id and (m.sender_id = auth.uid() or m.recipient_id = auth.uid())
  ));

drop policy if exists presence_read on public.presence;
create policy presence_read on public.presence for select to authenticated using (true);

drop policy if exists events_own_read on public.events;
create policy events_own_read on public.events for select to authenticated using (account_id = auth.uid());

drop policy if exists profiles_self_insert on public.profiles;
drop policy if exists profiles_self_update on public.profiles;
drop policy if exists contacts_owner_all on public.contacts;
drop policy if exists messages_sender_insert on public.messages;
drop policy if exists messages_sender_update on public.messages;
drop policy if exists messages_sender_delete on public.messages;
drop policy if exists receipts_recipient_write on public.receipts;
drop policy if exists receipts_recipient_update on public.receipts;
drop policy if exists reactions_own_write on public.reactions;
drop policy if exists presence_self_write on public.presence;

-- Writes are RPC-only. Tables are readable by authenticated users subject to
-- RLS, but never writable through PostgREST. SECURITY DEFINER functions are
-- owned by the migration role and therefore bypass these grants internally.
grant usage on schema public to authenticated;
grant select on public.profiles, public.contacts, public.messages,
  public.receipts, public.reactions, public.presence, public.events to authenticated;
grant usage, select on all sequences in schema public to authenticated;

create or replace function public.mp_profile_json(p public.profiles)
returns jsonb
language sql
stable
set search_path = public
as $$
  select jsonb_build_object(
    'id', p.id,
    'username', p.username,
    'displayName', p.display_name,
    'createdAt', (extract(epoch from p.created_at) * 1000)::bigint,
    'status', p.status,
    'phone', p.phone,
    'email', p.email,
    'bio', p.bio
  );
$$;

create or replace function public.mp_contact_json(c public.contacts)
returns jsonb
language sql
stable
set search_path = public
as $$
  select jsonb_build_object(
    'id', c.owner_id::text || '->' || c.contact_id::text,
    'ownerId', c.owner_id,
    'contactAccountId', c.contact_id,
    'nickname', c.nickname,
    'note', c.note,
    'favourite', c.favourite,
    'blocked', c.blocked,
    'hidden', c.hidden,
    'ringtone', c.ringtone,
    'updatedAt', (extract(epoch from c.updated_at) * 1000)::bigint
  );
$$;

create or replace function public.mp_message_for(p_viewer uuid, p_message public.messages)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_other uuid;
  v_receipt text;
  v_state text;
  v_reactions jsonb;
begin
  if p_message.id is null then
    return null;
  end if;
  v_other := case when p_message.sender_id = p_viewer then p_message.recipient_id else p_message.sender_id end;
  select r.state into v_receipt from public.receipts r
    where r.message_id = p_message.id and r.account_id = v_other;
  if p_message.sender_id = p_viewer then
    v_state := case when v_receipt = 'READ' then 'READ' when v_receipt = 'DELIVERED' then 'DELIVERED' else 'SENT' end;
  else
    select r.state into v_receipt from public.receipts r
      where r.message_id = p_message.id and r.account_id = p_viewer;
    v_state := case when v_receipt = 'READ' then 'READ' else 'DELIVERED' end;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('emoji', r.emoji, 'senderId', r.account_id, 'at', (extract(epoch from r.at) * 1000)::bigint) order by r.at), '[]'::jsonb)
    into v_reactions
    from public.reactions r where r.message_id = p_message.id;
  return jsonb_build_object(
    'id', p_message.id,
    'clientId', p_message.client_id,
    'conversationId', least(p_message.sender_id::text, p_message.recipient_id::text) || ':' || greatest(p_message.sender_id::text, p_message.recipient_id::text),
    'senderId', p_message.sender_id,
    'recipientId', p_message.recipient_id,
    'body', case when p_message.deleted then '' else p_message.body end,
    'createdAt', (extract(epoch from p_message.created_at) * 1000)::bigint,
    'state', v_state,
    'replyToId', p_message.reply_to,
    'editedAt', (extract(epoch from p_message.edited_at) * 1000)::bigint,
    'reactions', v_reactions,
    'deleted', p_message.deleted,
    'expiresAt', (extract(epoch from p_message.expires_at) * 1000)::bigint,
    'transcript', p_message.transcript,
    'seq', p_message.seq
  );
end;
$$;

create or replace function public.mp_append_event(p_account uuid, p_type text, p_payload jsonb)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.events (account_id, type, payload) values (p_account, p_type, p_payload);
$$;

revoke all on function public.mp_append_event(uuid, text, jsonb) from public, anon, authenticated;

create or replace function public.mp_on_message_insert()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.mp_append_event(new.recipient_id, 'message.created', public.mp_message_for(new.recipient_id, new));
  perform public.mp_append_event(new.sender_id, 'message.created', public.mp_message_for(new.sender_id, new));
  return new;
end;
$$;

create or replace function public.mp_on_message_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_type text := case when new.deleted and not old.deleted then 'message.deleted' else 'message.updated' end;
begin
  perform public.mp_append_event(new.recipient_id, v_type, public.mp_message_for(new.recipient_id, new));
  perform public.mp_append_event(new.sender_id, v_type, public.mp_message_for(new.sender_id, new));
  return new;
end;
$$;

create or replace function public.mp_on_receipt_write()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_message public.messages;
  v_type text;
begin
  select * into v_message from public.messages where id = new.message_id;
  if v_message.id is null then
    return new;
  end if;
  v_type := case when new.state = 'READ' then 'message.read' else 'message.delivered' end;
  perform public.mp_append_event(v_message.sender_id, v_type, public.mp_message_for(v_message.sender_id, v_message));
  return new;
end;
$$;

create or replace function public.mp_on_reaction_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_message public.messages;
  v_message_id uuid;
begin
  if tg_op = 'DELETE' then
    v_message_id := old.message_id;
  else
    v_message_id := new.message_id;
  end if;
  select * into v_message from public.messages where id = v_message_id;
  if v_message.id is not null then
    perform public.mp_append_event(v_message.sender_id, 'message.reactions', public.mp_message_for(v_message.sender_id, v_message));
    perform public.mp_append_event(v_message.recipient_id, 'message.reactions', public.mp_message_for(v_message.recipient_id, v_message));
  end if;
  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create or replace function public.mp_on_presence_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_watcher uuid;
begin
  for v_watcher in select owner_id from public.contacts where contact_id = new.account_id loop
    perform public.mp_append_event(v_watcher, 'presence.changed', jsonb_build_object(
      'accountId', new.account_id,
      'state', new.state,
      'lastSeen', (extract(epoch from new.last_seen) * 1000)::bigint,
      'availability', new.availability
    ));
  end loop;
  return new;
end;
$$;

drop trigger if exists messages_after_insert on public.messages;
create trigger messages_after_insert after insert on public.messages
  for each row execute function public.mp_on_message_insert();
drop trigger if exists messages_after_update on public.messages;
create trigger messages_after_update after update on public.messages
  for each row execute function public.mp_on_message_update();
drop trigger if exists receipts_after_write on public.receipts;
create trigger receipts_after_write after insert or update on public.receipts
  for each row execute function public.mp_on_receipt_write();
drop trigger if exists reactions_after_change on public.reactions;
create trigger reactions_after_change after insert or update or delete on public.reactions
  for each row execute function public.mp_on_reaction_change();
drop trigger if exists presence_after_write on public.presence;
create trigger presence_after_write after insert or update on public.presence
  for each row execute function public.mp_on_presence_change();

create or replace function public.mp_send_message(
  p_recipient uuid,
  p_body text,
  p_client_id text,
  p_reply_to uuid default null,
  p_expires_at timestamptz default null,
  p_transcript text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_existing public.messages;
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_recipient = v_uid then raise exception 'invalid_recipient'; end if;
  if char_length(coalesce(p_body, '')) < 1 or char_length(p_body) > 8000 then raise exception 'invalid_body'; end if;
  if char_length(coalesce(p_client_id, '')) < 1 or char_length(p_client_id) > 100 then raise exception 'invalid_client_id'; end if;
  if not exists (select 1 from public.profiles where id = p_recipient) then raise exception 'no_such_account'; end if;
  if exists (select 1 from public.contacts where owner_id = p_recipient and contact_id = v_uid and blocked) then
    raise exception 'blocked';
  end if;
  select * into v_existing from public.messages where sender_id = v_uid and client_id = p_client_id;
  if v_existing.id is not null then
    return public.mp_message_for(v_uid, v_existing);
  end if;
  insert into public.messages (client_id, sender_id, recipient_id, body, reply_to, expires_at, transcript)
  values (p_client_id, v_uid, p_recipient, p_body, p_reply_to, p_expires_at, p_transcript)
  returning * into v_message;
  return public.mp_message_for(v_uid, v_message);
end;
$$;

create or replace function public.mp_mark_delivered(p_peer uuid)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_count integer := 0;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  insert into public.receipts (message_id, account_id, state, at)
  select m.id, v_uid, 'DELIVERED', now()
  from public.messages m
  where m.sender_id = p_peer and m.recipient_id = v_uid
    and (m.expires_at is null or m.expires_at > now())
  on conflict (message_id, account_id) do nothing;
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

create or replace function public.mp_mark_read(p_message uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select * into v_message from public.messages where id = p_message;
  if v_message.id is null then raise exception 'no_such_message'; end if;
  if v_message.recipient_id <> v_uid then raise exception 'not_recipient'; end if;
  insert into public.receipts (message_id, account_id, state, at)
  values (p_message, v_uid, 'READ', now())
  on conflict (message_id, account_id) do update set state = 'READ', at = now();
  return public.mp_message_for(v_uid, v_message);
end;
$$;

create or replace function public.mp_edit_message(p_message uuid, p_body text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select * into v_message from public.messages where id = p_message;
  if v_message.id is null then raise exception 'no_such_message'; end if;
  if v_message.sender_id <> v_uid then raise exception 'not_sender'; end if;
  if v_message.deleted then raise exception 'deleted'; end if;
  if char_length(coalesce(p_body, '')) < 1 or char_length(p_body) > 8000 then raise exception 'invalid_body'; end if;
  update public.messages set body = p_body, edited_at = now() where id = p_message returning * into v_message;
  return public.mp_message_for(v_uid, v_message);
end;
$$;

create or replace function public.mp_delete_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select * into v_message from public.messages where id = p_message;
  if v_message.id is null then raise exception 'no_such_message'; end if;
  if v_message.sender_id <> v_uid then raise exception 'not_sender'; end if;
  update public.messages set deleted = true, body = '' where id = p_message;
end;
$$;

create or replace function public.mp_upsert_contact(
  p_contact uuid,
  p_nickname text default null,
  p_note text default null,
  p_favourite boolean default null,
  p_blocked boolean default null,
  p_hidden boolean default null,
  p_ringtone text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_contact public.contacts;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_contact = v_uid then raise exception 'invalid_contact'; end if;
  if not exists (select 1 from public.profiles where id = p_contact) then raise exception 'no_such_account'; end if;
  insert into public.contacts as c (owner_id, contact_id, nickname, note, favourite, blocked, hidden, ringtone, updated_at)
  values (
    v_uid, p_contact, p_nickname, p_note,
    coalesce(p_favourite, false), coalesce(p_blocked, false), coalesce(p_hidden, false), p_ringtone, now()
  )
  on conflict (owner_id, contact_id) do update set
    nickname = coalesce(p_nickname, c.nickname),
    note = coalesce(p_note, c.note),
    favourite = coalesce(p_favourite, c.favourite),
    blocked = coalesce(p_blocked, c.blocked),
    hidden = coalesce(p_hidden, c.hidden),
    ringtone = coalesce(p_ringtone, c.ringtone),
    updated_at = now()
  returning * into v_contact;
  if v_contact.blocked then
    perform public.mp_append_event(p_contact, 'contact.blocked', jsonb_build_object('by', v_uid, 'at', (extract(epoch from now()) * 1000)::bigint));
  end if;
  return public.mp_contact_json(v_contact);
end;
$$;

create or replace function public.mp_delete_contact(p_contact uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  delete from public.contacts where owner_id = v_uid and contact_id = p_contact;
end;
$$;

create or replace function public.mp_add_reaction(p_message uuid, p_emoji text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if char_length(coalesce(p_emoji, '')) < 1 or char_length(p_emoji) > 16 then raise exception 'invalid_emoji'; end if;
  select * into v_message from public.messages where id = p_message;
  if v_message.id is null then raise exception 'no_such_message'; end if;
  if v_message.sender_id <> v_uid and v_message.recipient_id <> v_uid then raise exception 'not_participant'; end if;
  insert into public.reactions (message_id, account_id, emoji, at)
  values (p_message, v_uid, p_emoji, now())
  on conflict (message_id, account_id) do update set emoji = excluded.emoji, at = now();
  return public.mp_message_for(v_uid, v_message);
end;
$$;

create or replace function public.mp_remove_reaction(p_message uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_message public.messages;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select * into v_message from public.messages where id = p_message;
  if v_message.id is null then raise exception 'no_such_message'; end if;
  if v_message.sender_id <> v_uid and v_message.recipient_id <> v_uid then raise exception 'not_participant'; end if;
  delete from public.reactions where message_id = p_message and account_id = v_uid;
  return public.mp_message_for(v_uid, v_message);
end;
$$;

create or replace function public.mp_list_contacts()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_result jsonb;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select coalesce(jsonb_agg(public.mp_contact_json(c) order by c.favourite desc, c.contact_id), '[]'::jsonb)
    into v_result from public.contacts c where c.owner_id = v_uid;
  return v_result;
end;
$$;

create or replace function public.mp_list_threads()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_result jsonb;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  with mine as (
    select m.*, case when m.sender_id = v_uid then m.recipient_id else m.sender_id end as peer_id
    from public.messages m
    where (m.sender_id = v_uid or m.recipient_id = v_uid)
      and (m.expires_at is null or m.expires_at > now())
  ), latest as (
    select distinct on (peer_id) peer_id, id, seq, created_at
    from mine order by peer_id, seq desc
  )
  select coalesce(jsonb_agg(jsonb_build_object(
      'peerId', l.peer_id,
      'threadId', least(v_uid::text, l.peer_id::text) || ':' || greatest(v_uid::text, l.peer_id::text),
      'lastMessage', public.mp_message_for(v_uid, m),
      'unread', (
        select count(*) from public.messages x
        where x.sender_id = l.peer_id and x.recipient_id = v_uid
          and (x.expires_at is null or x.expires_at > now())
          and not exists (
            select 1 from public.receipts r where r.message_id = x.id and r.account_id = v_uid and r.state = 'READ'
          )
      ),
      'favourite', coalesce(c.favourite, false),
      'blocked', coalesce(c.blocked, false),
      'muted', false,
      'updatedAt', (extract(epoch from l.created_at) * 1000)::bigint
    ) order by l.seq desc), '[]'::jsonb)
    into v_result
    from latest l
    join public.messages m on m.id = l.id
    left join public.contacts c on c.owner_id = v_uid and c.contact_id = l.peer_id;
  return v_result;
end;
$$;

create or replace function public.mp_list_messages(
  p_peer uuid,
  p_since bigint default 0,
  p_limit integer default 200
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_messages jsonb;
  v_cursor bigint;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_peer = v_uid then raise exception 'invalid_recipient'; end if;
  perform public.mp_mark_delivered(p_peer);
  with page as (
    select m.* from public.messages m
    where m.seq > coalesce(p_since, 0)
      and ((m.sender_id = v_uid and m.recipient_id = p_peer)
        or (m.sender_id = p_peer and m.recipient_id = v_uid))
      and (m.expires_at is null or m.expires_at > now())
    order by m.seq asc
    limit greatest(1, least(coalesce(p_limit, 200), 500))
  )
  select coalesce(jsonb_agg(public.mp_message_for(v_uid, m) order by m.seq), '[]'::jsonb),
    coalesce(max(m.seq), coalesce(p_since, 0))
    into v_messages, v_cursor
    from page m;
  return jsonb_build_object('messages', v_messages, 'cursor', v_cursor);
end;
$$;

create or replace function public.mp_sync(p_since bigint default 0, p_limit integer default 200)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_events jsonb;
  v_cursor bigint;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  with page as (
    select e.* from public.events e
    where e.account_id = v_uid and e.seq > coalesce(p_since, 0)
    order by e.seq asc
    limit greatest(1, least(coalesce(p_limit, 200), 500))
  )
  select coalesce(jsonb_agg(jsonb_build_object(
      'seq', e.seq,
      'type', e.type,
      'payload', e.payload,
      'at', (extract(epoch from e.at) * 1000)::bigint
    ) order by e.seq), '[]'::jsonb),
    coalesce(max(e.seq), coalesce(p_since, 0))
    into v_events, v_cursor
    from page e;
  return jsonb_build_object('events', v_events, 'cursor', v_cursor);
end;
$$;

create or replace function public.mp_set_presence(p_state text, p_availability text default 'Available')
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_presence public.presence;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_state not in ('ONLINE', 'OFFLINE') then raise exception 'invalid_state'; end if;
  insert into public.presence as p (account_id, state, last_seen, availability)
  values (v_uid, p_state, now(), coalesce(nullif(p_availability, ''), 'Available'))
  on conflict (account_id) do update set state = excluded.state, last_seen = now(), availability = excluded.availability
  returning * into v_presence;
  return jsonb_build_object(
    'accountId', v_presence.account_id,
    'state', v_presence.state,
    'lastSeen', (extract(epoch from v_presence.last_seen) * 1000)::bigint,
    'availability', v_presence.availability
  );
end;
$$;

create or replace function public.mp_get_presence(p_account uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_presence public.presence;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select * into v_presence from public.presence where account_id = p_account;
  if v_presence.account_id is null then return null; end if;
  return jsonb_build_object(
    'accountId', v_presence.account_id,
    'state', v_presence.state,
    'lastSeen', (extract(epoch from v_presence.last_seen) * 1000)::bigint,
    'availability', v_presence.availability
  );
end;
$$;

-- Registration profile creation is called only by the Edge Function with the
-- service role after Supabase Auth has created and confirmed the user.
create or replace function public.mp_register_profile(
  p_id uuid,
  p_username text,
  p_display_name text,
  p_phone text default null,
  p_email text default null,
  p_bio text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_profile public.profiles;
begin
  if not exists (select 1 from auth.users u where u.id = p_id) then raise exception 'no_such_account'; end if;
  if char_length(coalesce(p_username, '')) < 3 or char_length(p_username) > 24 then raise exception 'invalid_username'; end if;
  if p_username !~ '^[A-Za-z0-9._]+$' then raise exception 'invalid_username'; end if;
  insert into public.profiles (id, username, display_name, phone, email, bio)
  values (p_id, p_username, coalesce(nullif(p_display_name, ''), p_username), p_phone, p_email, p_bio)
  returning * into v_profile;
  return public.mp_profile_json(v_profile);
end;
$$;

revoke all on function public.mp_register_profile(uuid, text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.mp_register_profile(uuid, text, text, text, text, text) to service_role;

revoke all on function public.mp_append_event(uuid, text, jsonb) from public, anon, authenticated;
revoke all on function public.mp_message_for(uuid, public.messages) from public, anon, authenticated;
revoke all on function public.mp_profile_json(public.profiles) from public, anon, authenticated;
revoke all on function public.mp_contact_json(public.contacts) from public, anon, authenticated;

grant execute on function public.mp_send_message(uuid, text, text, uuid, timestamptz, text) to authenticated;
grant execute on function public.mp_mark_delivered(uuid) to authenticated;
grant execute on function public.mp_mark_read(uuid) to authenticated;
grant execute on function public.mp_edit_message(uuid, text) to authenticated;
grant execute on function public.mp_delete_message(uuid) to authenticated;
grant execute on function public.mp_upsert_contact(uuid, text, text, boolean, boolean, boolean, text) to authenticated;
grant execute on function public.mp_delete_contact(uuid) to authenticated;
grant execute on function public.mp_add_reaction(uuid, text) to authenticated;
grant execute on function public.mp_remove_reaction(uuid) to authenticated;
grant execute on function public.mp_list_contacts() to authenticated;
grant execute on function public.mp_list_threads() to authenticated;
grant execute on function public.mp_list_messages(uuid, bigint, integer) to authenticated;
grant execute on function public.mp_sync(bigint, integer) to authenticated;
grant execute on function public.mp_set_presence(text, text) to authenticated;
grant execute on function public.mp_get_presence(uuid) to authenticated;

-- Realtime is enabled for the tables a future client can subscribe to directly.
do $$
begin
  begin
    alter publication supabase_realtime add table public.messages;
  exception when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table public.receipts;
  exception when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table public.reactions;
  exception when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table public.presence;
  exception when duplicate_object then null;
  end;
end $$;
