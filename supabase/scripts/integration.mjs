#!/usr/bin/env node
// Live integration check for the deployed Messenger Pro Supabase function.
// Usage:
//   node supabase/scripts/integration.mjs send   --url <function-url> --state state.json
//   node supabase/scripts/integration.mjs verify --url <function-url> --state state.json
// The two phases are separate processes on purpose: `verify` proves that the
// accounts and the message survived a new function invocation.

import { readFileSync, writeFileSync } from "node:fs";

class ApiError extends Error {
  constructor(status, code, message) {
    super(message ?? `HTTP ${status}`);
    this.status = status;
    this.code = code;
  }
}

function parseArgs(argv) {
  const parsed = { phase: "all" };
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i];
    if (!value.startsWith("--")) {
      if (parsed.phase === "all" && ["send", "verify", "all"].includes(value)) {
        parsed.phase = value;
      }
      continue;
    }
    const key = value.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) throw new Error(`Missing value for --${key}`);
    parsed[key] = next;
    i++;
  }
  return parsed;
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const args = parseArgs(process.argv.slice(2));
const base = (args.url ?? process.env.MESSENGER_API_URL ?? "").replace(/\/+$/, "");
const statePath = args.state ?? process.env.MESSENGER_STATE ?? "integration-state.json";
if (!base) throw new Error("Pass --url or set MESSENGER_API_URL");

async function api(method, path, token = null, body = undefined) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await response.text();
  let data = null;
  if (raw) {
    try {
      data = JSON.parse(raw);
    } catch {
      data = null;
    }
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      data?.error?.code ?? "http_error",
      data?.error?.message ?? raw.slice(0, 200),
    );
  }
  return data;
}

async function expectError(label, status, code, task) {
  try {
    await task();
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
    assert(error.status === status, `${label}: expected ${status}, got ${error.status} (${error.message})`);
    if (code) assert(error.code === code, `${label}: expected code ${code}, got ${error.code}`);
    return;
  }
  throw new Error(`${label}: expected an HTTP ${status} error`);
}

function sessionCheck(label, session, username) {
  assert(session.token, `${label}: token missing`);
  assert(session.refreshToken, `${label}: refreshToken missing`);
  assert(typeof session.expiresAt === "number" && session.expiresAt > Date.now(), `${label}: expiresAt invalid`);
  assert(session.account?.id, `${label}: account id missing`);
  assert(session.account.username.toLowerCase() === username.toLowerCase(), `${label}: username mismatch`);
}

function writeState(state) {
  writeFileSync(statePath, JSON.stringify(state, null, 2));
}

function readState() {
  return JSON.parse(readFileSync(statePath, "utf8"));
}

async function sendPhase() {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
  const names = {
    alice: `ci${suffix}a`.slice(0, 24),
    bob: `ci${suffix}b`.slice(0, 24),
    carol: `ci${suffix}c`.slice(0, 24),
  };
  const password = "integration-pass-1";
  const users = {};

  for (const [key, username] of Object.entries(names)) {
    const session = await api("POST", "/v1/accounts", null, {
      username,
      displayName: `CI ${key}`,
      password,
    });
    sessionCheck(`register ${key}`, session, username);
    users[key] = {
      username,
      password,
      token: session.token,
      refreshToken: session.refreshToken,
      account: session.account,
    };
  }

  await expectError("duplicate username", 409, "username_taken", () =>
    api("POST", "/v1/accounts", null, {
      username: names.alice,
      displayName: "Duplicate",
      password,
    }));

  await expectError("case-insensitive duplicate username", 409, "username_taken", () =>
    api("POST", "/v1/accounts", null, {
      username: names.alice.toUpperCase(),
      displayName: "Duplicate",
      password,
    }));

  await expectError("wrong password", 401, "invalid_credentials", () =>
    api("POST", "/v1/sessions", null, { username: names.alice, password: "wrong-pass" }));

  const login = await api("POST", "/v1/sessions", null, {
    username: names.bob,
    password,
  });
  sessionCheck("login bob", login, names.bob);
  await api("DELETE", "/v1/sessions", login.token);

  const me = await api("GET", "/v1/me", users.alice.token);
  assert(me.account.id === users.alice.account.id, "me returned another account");

  const found = await api("GET", `/v1/accounts?username=${encodeURIComponent(names.bob)}`, users.alice.token);
  assert(found.account.id === users.bob.account.id, "username lookup mismatch");

  await expectError("missing username lookup", 404, "no_such_account", () =>
    api("GET", `/v1/accounts?username=definitely_missing_${suffix}`, users.alice.token));

  const contact = await api("PUT", `/v1/contacts/${users.bob.account.id}`, users.alice.token, {
    favourite: true,
  });
  assert(contact.contact.contactAccountId === users.bob.account.id, "contact upsert mismatch");
  assert(contact.contact.favourite === true, "contact favourite not stored");

  const clientId = `ci-${suffix}`;
  const sent = await api("POST", "/v1/messages", users.alice.token, {
    to: users.bob.account.id,
    body: "hello from CI",
    clientId,
  });
  assert(sent.message?.id, "send did not return a message id");
  assert(sent.message.state === "SENT", `unexpected initial state ${sent.message.state}`);

  const duplicate = await api("POST", "/v1/messages", users.alice.token, {
    to: users.bob.account.id,
    body: "hello from CI",
    clientId,
  });
  assert(duplicate.message.id === sent.message.id, "clientId idempotency failed");

  const aliceSync = await api("GET", "/v1/sync?since=0", users.alice.token);
  assert(aliceSync.events.length >= 1, "alice has no sync events after send");
  assert(aliceSync.events.some((event) => event.type === "message.created"), "message.created event missing");

  const state = {
    base,
    createdAt: Date.now(),
    users,
    clientId,
    messageId: sent.message.id,
    aliceCursor: aliceSync.cursor,
  };
  writeState(state);
  console.log(`send phase ok: registered ${Object.values(names).join(", ")} and sent ${sent.message.id}`);
}

async function verifyPhase() {
  const state = readState();
  assert(state.base === base, `state was written for ${state.base}, not ${base}`);
  const { users, messageId, clientId } = state;
  assert(users?.alice?.token && users?.bob?.token && users?.carol?.token, "state is incomplete");

  let aliceToken = users.alice.token;
  let bobToken = users.bob.token;
  const carolToken = users.carol.token;

  const bobPage = await api("GET", `/v1/messages/${users.alice.account.id}?since=0`, bobToken);
  assert(bobPage.messages.length === 1, `bob expected 1 message, got ${bobPage.messages.length}`);
  const received = bobPage.messages[0];
  assert(received.id === messageId, "bob received a different message id");
  assert(received.body === "hello from CI", `body mismatch: ${received.body}`);
  assert(received.state === "DELIVERED", `bob state expected DELIVERED, got ${received.state}`);

  const read = await api("POST", `/v1/messages/${messageId}/read`, bobToken);
  assert(read.message.state === "READ", `read state expected READ, got ${read.message.state}`);

  const receiptSync = await api("GET", `/v1/sync?since=${state.aliceCursor}`, aliceToken);
  const receiptTypes = receiptSync.events.map((event) => event.type);
  assert(receiptTypes.includes("message.delivered"), `missing delivered event: ${receiptTypes}`);
  assert(receiptTypes.includes("message.read"), `missing read event: ${receiptTypes}`);

  const reaction = await api("POST", `/v1/messages/${messageId}/reactions`, bobToken, { emoji: "👍" });
  assert(reaction.message.reactions.length === 1, "reaction was not added");
  const unreaction = await api("DELETE", `/v1/messages/${messageId}/reactions`, bobToken);
  assert(unreaction.message.reactions.length === 0, "reaction was not removed");

  const edited = await api("PATCH", `/v1/messages/${messageId}`, aliceToken, { body: "edited by CI" });
  assert(edited.message.body === "edited by CI", "edit did not persist");
  assert(edited.message.editedAt, "editedAt missing");

  await expectError("non-sender edit", 403, "not_sender", () =>
    api("PATCH", `/v1/messages/${messageId}`, bobToken, { body: "hijack" }));

  const threads = await api("GET", "/v1/threads", aliceToken);
  assert(threads.threads.length === 1, `alice expected 1 thread, got ${threads.threads.length}`);
  assert(threads.threads[0].peerId === users.bob.account.id, "thread peer mismatch");

  const contacts = await api("GET", "/v1/contacts", aliceToken);
  assert(contacts.contacts.length === 1, "alice expected 1 contact");
  assert(contacts.contacts[0].favourite === true, "contact favourite lost");

  const presence = await api("POST", "/v1/presence", bobToken, {
    state: "ONLINE",
    availability: "Busy",
  });
  assert(presence.presence.availability === "Busy", "presence not stored");
  const seen = await api("GET", `/v1/presence/${users.bob.account.id}`, aliceToken);
  assert(seen.presence?.availability === "Busy", "presence lookup mismatch");

  const carolPage = await api("GET", `/v1/messages/${users.alice.account.id}?since=0`, carolToken);
  assert(carolPage.messages.length === 0, "carol can read alice messages");
  const carolSync = await api("GET", "/v1/sync?since=0", carolToken);
  assert(carolSync.events.length === 0, "carol receives alice/bob events");
  const carolThreads = await api("GET", "/v1/threads", carolToken);
  assert(carolThreads.threads.length === 0, "carol sees alice threads");

  await api("PUT", `/v1/contacts/${users.alice.account.id}`, bobToken, { blocked: true });
  await expectError("blocked send", 403, "blocked", () =>
    api("POST", "/v1/messages", aliceToken, {
      to: users.bob.account.id,
      body: "should not arrive",
      clientId: `${clientId}-blocked`,
    }));
  await api("PUT", `/v1/contacts/${users.alice.account.id}`, bobToken, { blocked: false });

  await api("DELETE", `/v1/messages/${messageId}`, aliceToken);
  const deletedForBob = await api("GET", `/v1/messages/${users.alice.account.id}?since=0`, bobToken);
  const deleted = deletedForBob.messages.find((message) => message.id === messageId);
  assert(deleted && deleted.deleted === true, "deletion not visible to recipient");
  assert(deleted.body === "", "deletion did not clear the body");

  const refreshed = await api("POST", "/v1/sessions/refresh", null, {
    refreshToken: users.alice.refreshToken,
  });
  sessionCheck("refresh alice", refreshed, users.alice.username);
  aliceToken = refreshed.token;

  const refreshedMe = await api("GET", "/v1/me", aliceToken);
  assert(refreshedMe.account.id === users.alice.account.id, "refreshed token belongs to another account");

  const relogin = await api("POST", "/v1/sessions", null, {
    username: users.bob.username,
    password: users.bob.password,
  });
  bobToken = relogin.token;
  assert(relogin.account.id === users.bob.account.id, "login returned another account");

  await api("DELETE", "/v1/sessions", aliceToken);
  await api("DELETE", "/v1/sessions", bobToken);
  await api("DELETE", "/v1/sessions", carolToken);

  await expectError("refresh after logout", 401, "invalid_refresh_token", () =>
    api("POST", "/v1/sessions/refresh", null, { refreshToken: refreshed.refreshToken }));

  console.log(`verify phase ok: message ${messageId} persisted, receipts, reactions, presence and isolation verified`);
}

if (!["send", "verify", "all"].includes(args.phase)) {
  throw new Error(`Unknown phase ${args.phase}`);
}

if (args.phase === "send" || args.phase === "all") await sendPhase();
if (args.phase === "verify" || args.phase === "all") await verifyPhase();
