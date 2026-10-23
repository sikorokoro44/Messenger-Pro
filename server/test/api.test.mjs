import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openDb } from '../src/db.mjs';
import { Store } from '../src/store.mjs';
import { createApp } from '../src/app.mjs';

async function start() {
  const dir = mkdtempSync(join(tmpdir(), 'mp-server-'));
  const db = openDb(join(dir, 'test.db'));
  const { server } = createApp({ store: new Store(db), secret: 'test-secret', logger: { error() {} } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    base,
    db,
    async close() {
      server.closeAllConnections?.();
      await new Promise(resolve => server.close(resolve));
      db.close();
      rmSync(dir, { recursive: true, force: true });
    }
  };
}

async function api(base, method, path, { token, body } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const raw = await response.text();
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    parsed = raw;
  }
  return { status: response.status, body: parsed };
}

async function register(base, username) {
  const result = await api(base, 'POST', '/v1/accounts', {
    body: { username, displayName: username.toUpperCase(), password: 'password1' }
  });
  assert.equal(result.status, 201, JSON.stringify(result.body));
  return result.body;
}

test('health check responds', async () => {
  const ctx = await start();
  try {
    const result = await api(ctx.base, 'GET', '/healthz');
    assert.equal(result.status, 200);
    assert.equal(result.body.ok, true);
  } finally {
    await ctx.close();
  }
});

test('account registration validation and duplicates', async () => {
  const ctx = await start();
  try {
    const created = await register(ctx.base, 'alice');
    assert.ok(created.token);
    assert.equal(created.account.username, 'alice');
    assert.equal((await api(ctx.base, 'GET', '/v1/me', { token: created.token })).body.account.username, 'alice');

    const duplicate = await api(ctx.base, 'POST', '/v1/accounts', {
      body: { username: 'ALICE', displayName: 'Other', password: 'password1' }
    });
    assert.equal(duplicate.status, 409);

    const invalid = await api(ctx.base, 'POST', '/v1/accounts', {
      body: { username: 'ab', displayName: 'A', password: 'password1' }
    });
    assert.equal(invalid.status, 400);
    assert.equal(invalid.body.error.code, 'invalid_username');

    const short = await api(ctx.base, 'POST', '/v1/accounts', {
      body: { username: 'charlie', displayName: 'C', password: 'short' }
    });
    assert.equal(short.status, 400);
  } finally {
    await ctx.close();
  }
});

test('login, invalid credentials and logout', async () => {
  const ctx = await start();
  try {
    await register(ctx.base, 'alice');
    const bad = await api(ctx.base, 'POST', '/v1/sessions', { body: { username: 'alice', password: 'wrongpass' } });
    assert.equal(bad.status, 401);
    const ok = await api(ctx.base, 'POST', '/v1/sessions', { body: { username: 'alice', password: 'password1' } });
    assert.equal(ok.status, 200);
    const me = await api(ctx.base, 'GET', '/v1/me', { token: ok.body.token });
    assert.equal(me.status, 200);
    const out = await api(ctx.base, 'DELETE', '/v1/sessions', { token: ok.body.token });
    assert.equal(out.status, 200);
    const revoked = await api(ctx.base, 'GET', '/v1/me', { token: ok.body.token });
    assert.equal(revoked.status, 401);
    const missing = await api(ctx.base, 'GET', '/v1/me');
    assert.equal(missing.status, 401);
  } finally {
    await ctx.close();
  }
});

test('message send, idempotency, delivery and read receipts', async () => {
  const ctx = await start();
  try {
    const alice = await register(ctx.base, 'alice');
    const bob = await register(ctx.base, 'bob');
    await api(ctx.base, 'PUT', `/v1/contacts/${bob.account.id}`, { token: alice.token, body: { favourite: true } });

    const sent = await api(ctx.base, 'POST', '/v1/messages', {
      token: alice.token,
      body: { to: bob.account.id, body: 'hello bob', clientId: 'client-1' }
    });
    assert.equal(sent.status, 201);
    assert.equal(sent.body.message.state, 'SENT');
    assert.equal(sent.body.message.body, 'hello bob');

    const duplicate = await api(ctx.base, 'POST', '/v1/messages', {
      token: alice.token,
      body: { to: bob.account.id, body: 'hello bob', clientId: 'client-1' }
    });
    assert.equal(duplicate.body.message.id, sent.body.message.id);

    const bobInbox = await api(ctx.base, 'GET', `/v1/messages/${alice.account.id}`, { token: bob.token });
    assert.equal(bobInbox.status, 200);
    assert.equal(bobInbox.body.messages.length, 1);
    assert.equal(bobInbox.body.messages[0].state, 'DELIVERED');

    const aliceThread = await api(ctx.base, 'GET', `/v1/messages/${bob.account.id}`, { token: alice.token });
    assert.equal(aliceThread.body.messages[0].state, 'DELIVERED');

    const read = await api(ctx.base, 'POST', `/v1/messages/${sent.body.message.id}/read`, { token: bob.token });
    assert.equal(read.status, 200);
    assert.equal(read.body.message.state, 'READ');

    const after = await api(ctx.base, 'GET', `/v1/messages/${bob.account.id}`, { token: alice.token });
    assert.equal(after.body.messages[0].state, 'READ');

    const sync = await api(ctx.base, 'GET', '/v1/sync?since=0', { token: alice.token });
    assert.ok(sync.body.events.some(e => e.type === 'message.read'));
    assert.ok(sync.body.cursor > 0);
    const empty = await api(ctx.base, 'GET', `/v1/sync?since=${sync.body.cursor}`, { token: alice.token });
    assert.equal(empty.body.events.length, 0);

    const threads = await api(ctx.base, 'GET', '/v1/threads', { token: bob.token });
    assert.equal(threads.body.threads.length, 1);
    assert.equal(threads.body.threads[0].peerId, alice.account.id);

    const found = await api(ctx.base, 'GET', '/v1/accounts?username=alice', { token: bob.token });
    assert.equal(found.status, 200);
    assert.equal(found.body.account.id, alice.account.id);
    const missing = await api(ctx.base, 'GET', '/v1/accounts?username=nobody', { token: bob.token });
    assert.equal(missing.status, 404);
  } finally {
    await ctx.close();
  }
});

test('blocked accounts cannot send', async () => {
  const ctx = await start();
  try {
    const alice = await register(ctx.base, 'alice');
    const bob = await register(ctx.base, 'bob');
    await api(ctx.base, 'PUT', `/v1/contacts/${alice.account.id}`, { token: bob.token, body: { blocked: true } });
    const blocked = await api(ctx.base, 'POST', '/v1/messages', {
      token: alice.token,
      body: { to: bob.account.id, body: 'nope', clientId: 'c1' }
    });
    assert.equal(blocked.status, 403);
    assert.equal(blocked.body.error.code, 'blocked');
    assert.equal((await api(ctx.base, 'GET', '/v1/contacts', { token: bob.token })).body.contacts[0].blocked, true);
  } finally {
    await ctx.close();
  }
});

test('edit, delete and reactions enforce ownership', async () => {
  const ctx = await start();
  try {
    const alice = await register(ctx.base, 'alice');
    const bob = await register(ctx.base, 'bob');
    const sent = await api(ctx.base, 'POST', '/v1/messages', {
      token: alice.token,
      body: { to: bob.account.id, body: 'original', clientId: 'c2' }
    });
    const id = sent.body.message.id;

    const bobEdit = await api(ctx.base, 'PATCH', `/v1/messages/${id}`, { token: bob.token, body: { body: 'hacked' } });
    assert.equal(bobEdit.status, 403);
    const edit = await api(ctx.base, 'PATCH', `/v1/messages/${id}`, { token: alice.token, body: { body: 'edited' } });
    assert.equal(edit.body.message.body, 'edited');
    assert.ok(edit.body.message.editedAt);

    const reaction = await api(ctx.base, 'POST', `/v1/messages/${id}/reactions`, { token: bob.token, body: { emoji: '👍' } });
    assert.equal(reaction.body.message.reactions.length, 1);
    assert.equal(reaction.body.message.reactions[0].senderId, bob.account.id);
    const removed = await api(ctx.base, 'DELETE', `/v1/messages/${id}/reactions`, { token: bob.token });
    assert.equal(removed.body.message.reactions.length, 0);

    const bobDelete = await api(ctx.base, 'DELETE', `/v1/messages/${id}?forEveryone=1`, { token: bob.token });
    assert.equal(bobDelete.status, 403);
    const deleted = await api(ctx.base, 'DELETE', `/v1/messages/${id}?forEveryone=1`, { token: alice.token });
    assert.equal(deleted.status, 200);
    const bobView = await api(ctx.base, 'GET', `/v1/messages/${alice.account.id}`, { token: bob.token });
    assert.equal(bobView.body.messages[0].deleted, true);
    assert.equal(bobView.body.messages[0].body, '');
  } finally {
    await ctx.close();
  }
});

test('presence updates reach watchers', async () => {
  const ctx = await start();
  try {
    const alice = await register(ctx.base, 'alice');
    const bob = await register(ctx.base, 'bob');
    await api(ctx.base, 'PUT', `/v1/contacts/${bob.account.id}`, { token: alice.token, body: {} });
    const update = await api(ctx.base, 'POST', '/v1/presence', {
      token: bob.token,
      body: { state: 'ONLINE', availability: 'Busy' }
    });
    assert.equal(update.status, 200);
    assert.equal(update.body.presence.availability, 'Busy');
    const sync = await api(ctx.base, 'GET', '/v1/sync?since=0', { token: alice.token });
    const presence = sync.body.events.find(e => e.type === 'presence.changed');
    assert.ok(presence);
    assert.equal(presence.payload.accountId, bob.account.id);
    const fetched = await api(ctx.base, 'GET', `/v1/presence/${bob.account.id}`, { token: alice.token });
    assert.equal(fetched.body.presence.state, 'ONLINE');
  } finally {
    await ctx.close();
  }
});

test('server-sent events stream backlog', async () => {
  const ctx = await start();
  try {
    const alice = await register(ctx.base, 'alice');
    const bob = await register(ctx.base, 'bob');
    await api(ctx.base, 'POST', '/v1/messages', {
      token: alice.token,
      body: { to: bob.account.id, body: 'stream me', clientId: 'sse-1' }
    });
    const controller = new AbortController();
    const response = await fetch(`${ctx.base}/v1/events?since=0`, {
      headers: { authorization: `Bearer ${bob.token}` },
      signal: controller.signal
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /text\/event-stream/);
    const reader = response.body.getReader();
    const { value } = await reader.read();
    const chunk = Buffer.from(value).toString('utf8');
    assert.match(chunk, /event: message\.created/);
    assert.match(chunk, /stream me/);
    controller.abort();
  } finally {
    await ctx.close();
  }
});

test('login attempts are rate limited', async () => {
  const ctx = await start();
  try {
    await register(ctx.base, 'alice');
    let last;
    for (let i = 0; i < 9; i++) {
      last = await api(ctx.base, 'POST', '/v1/sessions', { body: { username: 'alice', password: 'wrongpass' } });
    }
    assert.equal(last.status, 429);
    assert.equal(last.body.error.code, 'rate_limited');
  } finally {
    await ctx.close();
  }
});
