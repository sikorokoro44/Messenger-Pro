-- End-to-end SQL verification for the Messenger Pro Supabase migration.
-- Runs inside one transaction and rolls everything back. All RPC calls are
-- made as the `authenticated` role with a request.jwt.claim.sub so the same
-- auth.uid() checks used in production are exercised.

\set ON_ERROR_STOP on
begin;

insert into auth.users (id, email) values
  ('00000000-0000-4000-8000-000000000001', 'alice@test.invalid'),
  ('00000000-0000-4000-8000-000000000002', 'bob@test.invalid'),
  ('00000000-0000-4000-8000-000000000003', 'carol@test.invalid'),
  ('00000000-0000-4000-8000-000000000004', 'dave@test.invalid');

set local role service_role;
select public.mp_register_profile(
  '00000000-0000-4000-8000-000000000001', 'alice', 'Alice', null, 'alice@example.test', null);
select public.mp_register_profile(
  '00000000-0000-4000-8000-000000000002', 'bob', 'Bob', null, null, null);
select public.mp_register_profile(
  '00000000-0000-4000-8000-000000000003', 'carol', 'Carol', null, null, null);
reset role;

do $$
begin
  begin
    perform public.mp_register_profile(
      '00000000-0000-4000-8000-000000000004', 'ALICE', 'Duplicate');
    raise exception 'expected case-insensitive duplicate username to be rejected';
  exception
    when unique_violation then null;
  end;
end $$;

create temp table test_state (k text primary key, v text);
grant all on test_state to authenticated;

set local role authenticated;

-- ---------------------------------------------------------------------------
-- Alice: contacts, send, idempotency, sync cursor
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000001', true);

do $$
declare
  v_uid uuid := current_setting('request.jwt.claim.sub')::uuid;
  v_contact jsonb;
  v_msg jsonb;
  v_again jsonb;
  v_page jsonb;
  v_sync jsonb;
begin
  if v_uid <> '00000000-0000-4000-8000-000000000001'::uuid then
    raise exception 'auth.uid() did not pick up the test claim';
  end if;
  if (select count(*) from public.messages) <> 0 then
    raise exception 'RLS leaked messages to Alice before any send';
  end if;

  v_contact := public.mp_upsert_contact(
    '00000000-0000-4000-8000-000000000002', null, null, true, null, null, null);
  if (v_contact->>'contactAccountId') <> '00000000-0000-4000-8000-000000000002' then
    raise exception 'contact upsert returned %', v_contact;
  end if;
  if not (v_contact->>'favourite')::boolean then
    raise exception 'favourite flag was not stored';
  end if;

  v_msg := public.mp_send_message(
    '00000000-0000-4000-8000-000000000002', 'hello bob', 'client-1', null, null, null);
  if v_msg->>'body' <> 'hello bob' then
    raise exception 'send body mismatch: %', v_msg;
  end if;
  if v_msg->>'state' <> 'SENT' then
    raise exception 'sender state should be SENT, was %', v_msg->>'state';
  end if;
  insert into test_state values ('msg_id', v_msg->>'id');

  v_again := public.mp_send_message(
    '00000000-0000-4000-8000-000000000002', 'hello bob', 'client-1', null, null, null);
  if v_again->>'id' <> v_msg->>'id' then
    raise exception 'clientId idempotency failed: % vs %', v_again->>'id', v_msg->>'id';
  end if;

  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000002', 0, 200);
  if jsonb_array_length(v_page->'messages') <> 1 then
    raise exception 'Alice thread page expected 1 message, got %', v_page;
  end if;

  v_sync := public.mp_sync(0, 200);
  if jsonb_array_length(v_sync->'events') < 1 then
    raise exception 'Alice sync returned no events after send';
  end if;
  insert into test_state values ('alice_cursor', v_sync->>'cursor');

  begin
    perform public.mp_send_message(
      '00000000-0000-4000-8000-000000000001', 'self', 'self-1', null, null, null);
    raise exception 'guard failed: self-send unexpectedly succeeded';
  exception
    when others then
      if sqlerrm not like '%invalid_recipient%' then raise; end if;
  end;
end $$;

-- ---------------------------------------------------------------------------
-- Bob: receive, delivery receipt, read receipt, presence, blocking
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000002', true);

do $$
declare
  v_uid uuid := current_setting('request.jwt.claim.sub')::uuid;
  v_msg_id uuid := (select v::uuid from test_state where k = 'msg_id');
  v_page jsonb;
  v_msg jsonb;
  v_sync jsonb;
  v_presence jsonb;
begin
  if (select count(*) from public.messages) <> 1 then
    raise exception 'RLS: Bob should see exactly one message';
  end if;

  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000001', 0, 200);
  if jsonb_array_length(v_page->'messages') <> 1 then
    raise exception 'Bob thread page expected 1 message, got %', v_page;
  end if;
  v_msg := v_page->'messages'->0;
  if v_msg->>'state' <> 'DELIVERED' then
    raise exception 'recipient state should be DELIVERED, was %', v_msg->>'state';
  end if;

  v_msg := public.mp_mark_read(v_msg_id);
  if v_msg->>'state' <> 'READ' then
    raise exception 'read state should be READ, was %', v_msg->>'state';
  end if;

  v_sync := public.mp_sync(0, 200);
  if jsonb_array_length(v_sync->'events') < 1 then
    raise exception 'Bob sync returned no events';
  end if;

  v_presence := public.mp_set_presence('ONLINE', 'Busy');
  if v_presence->>'availability' <> 'Busy' then
    raise exception 'presence availability not stored: %', v_presence;
  end if;

  begin
    perform public.mp_edit_message(v_msg_id, 'hijacked');
    raise exception 'guard failed: non-sender edit unexpectedly succeeded';
  exception
    when others then
      if sqlerrm not like '%not_sender%' then raise; end if;
  end;

  perform public.mp_upsert_contact(
    '00000000-0000-4000-8000-000000000001', null, null, null, true, null, null);
end $$;

-- ---------------------------------------------------------------------------
-- Alice: read state, receipt events, reactions, edit, presence lookup
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000001', true);

do $$
declare
  v_msg_id uuid := (select v::uuid from test_state where k = 'msg_id');
  v_cursor bigint := (select v::bigint from test_state where k = 'alice_cursor');
  v_page jsonb;
  v_sync jsonb;
  v_msg jsonb;
  v_types text[];
  v_presence jsonb;
begin
  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000002', 0, 200);
  if (v_page->'messages'->0->>'state') <> 'READ' then
    raise exception 'sender did not observe READ state: %', v_page;
  end if;

  v_sync := public.mp_sync(v_cursor, 200);
  select array_agg(e->>'type') into v_types
    from jsonb_array_elements(v_sync->'events') e;
  if v_types is null then
    raise exception 'no receipt events received after cursor %', v_cursor;
  end if;
  if not ('message.delivered' = any(v_types)) then
    raise exception 'missing message.delivered event: %', v_types;
  end if;
  if not ('message.read' = any(v_types)) then
    raise exception 'missing message.read event: %', v_types;
  end if;

  v_msg := public.mp_add_reaction(v_msg_id, '👍');
  if jsonb_array_length(v_msg->'reactions') <> 1 then
    raise exception 'reaction was not added: %', v_msg;
  end if;
  v_msg := public.mp_remove_reaction(v_msg_id);
  if jsonb_array_length(v_msg->'reactions') <> 0 then
    raise exception 'reaction was not removed: %', v_msg;
  end if;

  v_msg := public.mp_edit_message(v_msg_id, 'edited body');
  if v_msg->>'body' <> 'edited body' or v_msg->'editedAt' is null then
    raise exception 'edit did not persist: %', v_msg;
  end if;

  v_presence := public.mp_get_presence('00000000-0000-4000-8000-000000000002');
  if v_presence->>'availability' <> 'Busy' or v_presence->>'state' <> 'ONLINE' then
    raise exception 'presence lookup mismatch: %', v_presence;
  end if;

  begin
    perform public.mp_send_message(
      '00000000-0000-4000-8000-000000000002', 'blocked?', 'client-2', null, null, null);
    raise exception 'guard failed: blocked send unexpectedly succeeded';
  exception
    when others then
      if sqlerrm not like '%blocked%' then raise; end if;
  end;
end $$;

-- ---------------------------------------------------------------------------
-- Carol: full isolation from Alice and Bob
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000003', true);

do $$
declare
  v_page jsonb;
  v_sync jsonb;
  v_threads jsonb;
begin
  if (select count(*) from public.messages) <> 0 then
    raise exception 'RLS leaked messages to Carol';
  end if;
  if (select count(*) from public.events) <> 0 then
    raise exception 'RLS leaked events to Carol';
  end if;

  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000001', 0, 200);
  if jsonb_array_length(v_page->'messages') <> 0 then
    raise exception 'Carol saw Alice messages: %', v_page;
  end if;

  v_sync := public.mp_sync(0, 200);
  if jsonb_array_length(v_sync->'events') <> 0 then
    raise exception 'Carol sync leaked events: %', v_sync;
  end if;

  v_threads := public.mp_list_threads();
  if jsonb_array_length(v_threads) <> 0 then
    raise exception 'Carol saw threads: %', v_threads;
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Bob unblocks, Alice sends again and deletes, Bob reads the deletion
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000002', true);
do $$
begin
  perform public.mp_upsert_contact(
    '00000000-0000-4000-8000-000000000001', null, null, null, false, null, null);
end $$;

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000001', true);
do $$
declare
  v_msg jsonb;
  v_page jsonb;
begin
  v_msg := public.mp_send_message(
    '00000000-0000-4000-8000-000000000002', 'second message', 'client-3', null, null, null);
  insert into test_state values ('msg2_id', v_msg->>'id');

  perform public.mp_delete_message((v_msg->>'id')::uuid);

  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000002', 0, 200);
  if jsonb_array_length(v_page->'messages') <> 2 then
    raise exception 'expected two messages in Alice thread: %', v_page;
  end if;
  if not (v_page->'messages'->1->>'deleted')::boolean then
    raise exception 'deleted flag missing: %', v_page;
  end if;
  if (v_page->'messages'->1->>'body') <> '' then
    raise exception 'deleted body was not cleared: %', v_page;
  end if;
end $$;

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000002', true);
do $$
declare
  v_page jsonb;
  v_threads jsonb;
begin
  v_page := public.mp_list_messages('00000000-0000-4000-8000-000000000001', 0, 200);
  if jsonb_array_length(v_page->'messages') <> 2 then
    raise exception 'Bob should see both messages, got %', v_page;
  end if;
  if not (v_page->'messages'->1->>'deleted')::boolean then
    raise exception 'Bob did not observe deletion: %', v_page;
  end if;

  v_threads := public.mp_list_threads();
  if jsonb_array_length(v_threads) <> 1 then
    raise exception 'Bob thread list mismatch: %', v_threads;
  end if;
  if (v_threads->0->>'peerId') <> '00000000-0000-4000-8000-000000000001' then
    raise exception 'Bob thread peer mismatch: %', v_threads;
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Direct table writes must be denied; only RPCs may mutate state
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000001', true);
do $$
begin
  begin
    insert into public.messages (client_id, sender_id, recipient_id, body)
    values (
      'direct-write', '00000000-0000-4000-8000-000000000001',
      '00000000-0000-4000-8000-000000000002', 'nope');
    raise exception 'expected direct message insert to be denied';
  exception
    when insufficient_privilege then null;
  end;

  begin
    update public.messages set body = 'nope';
    raise exception 'expected direct message update to be denied';
  exception
    when insufficient_privilege then null;
  end;

  begin
    perform public.mp_register_profile(
      '00000000-0000-4000-8000-000000000004', 'dave', 'Dave');
    raise exception 'expected mp_register_profile to be denied to authenticated';
  exception
    when insufficient_privilege then null;
  end;
end $$;

rollback;
