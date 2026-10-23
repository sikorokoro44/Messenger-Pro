import { createClient, type SupabaseClient } from "@supabase/supabase-js";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ??
  Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const EMAIL_DOMAIN = "messenger-pro.invalid";
const USERNAME = /^[A-Za-z0-9._]{3,24}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization,apikey,content-type,x-client-info",
  "Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
  "Access-Control-Max-Age": "600",
};

class ApiError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

type Body = Record<string, unknown>;
// deno-lint-ignore no-explicit-any
type Client = SupabaseClient<any, "public", any>;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

function fail(error: unknown): Response {
  if (error instanceof ApiError) {
    return json(error.status, { error: { code: error.code, message: error.message } });
  }
  console.error("messenger-api unhandled error", error);
  return json(500, { error: { code: "internal_error", message: "Internal server error" } });
}

function apiPath(pathname: string): string {
  let path = pathname;
  for (const prefix of ["/functions/v1/messenger-api", "/messenger-api"]) {
    if (path === prefix) return "/";
    if (path.startsWith(prefix + "/")) {
      path = path.slice(prefix.length);
      break;
    }
  }
  if (path === "/healthz" || path.startsWith("/v1/")) return path;
  const nested = path.lastIndexOf("/v1/");
  if (nested > 0) return path.slice(nested);
  if (path.endsWith("/healthz")) return "/healthz";
  return path;
}

function bearer(req: Request): string | null {
  const header = req.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  return match ? match[1].trim() : null;
}

async function readBody(req: Request): Promise<Body> {
  const raw = await req.text();
  if (!raw.trim()) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new ApiError(400, "invalid_json", "Body must be valid JSON");
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new ApiError(400, "invalid_json", "Body must be a JSON object");
  }
  return parsed as Body;
}

function text(value: unknown, field: string, min = 1, max = 8000): string {
  if (typeof value !== "string") {
    throw new ApiError(400, "invalid_" + field, `${field} must be a string`);
  }
  const trimmed = value.trim();
  if (trimmed.length < min) {
    throw new ApiError(400, "invalid_" + field, `${field} is too short`);
  }
  if (trimmed.length > max) {
    throw new ApiError(400, "invalid_" + field, `${field} is too long`);
  }
  return trimmed;
}

function optionalText(value: unknown, field: string, max = 500): string | null {
  if (value === undefined || value === null || value === "") return null;
  return text(value, field, 1, max);
}

function optionalBool(value: unknown, field: string): boolean | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "boolean") {
    throw new ApiError(400, "invalid_" + field, `${field} must be a boolean`);
  }
  return value;
}

function numberParam(value: string | null, fallback: number, min: number, max: number): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(min, Math.min(max, Math.trunc(parsed)));
}

function timestamp(value: unknown, field: string): string | null {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value === "number" && Number.isFinite(value)) {
    return new Date(value).toISOString();
  }
  if (typeof value === "string") {
    const parsed = new Date(value);
    if (!Number.isNaN(parsed.getTime())) return parsed.toISOString();
  }
  throw new ApiError(400, "invalid_" + field, `${field} must be a timestamp`);
}

function uuidParam(value: string, field: string): string {
  const decoded = decodeURIComponent(value);
  if (!UUID.test(decoded)) {
    throw new ApiError(400, "invalid_" + field, `${field} must be a UUID`);
  }
  return decoded;
}

function mapDbError(error: { message?: string; code?: string } | null): ApiError {
  const message = error?.message ?? "";
  const code = error?.code ?? "";
  if (code === "23505" || message.includes("duplicate key")) {
    return new ApiError(409, "username_taken", "That username is already in use");
  }
  if (code === "22P02") {
    return new ApiError(400, "invalid_id", "The supplied identifier is not valid");
  }
  const known: Record<string, [number, string]> = {
    not_authenticated: [401, "Token is missing or expired"],
    invalid_token: [401, "Token is invalid"],
    no_such_account: [404, "No account with that identifier"],
    no_such_message: [404, "No such message"],
    invalid_recipient: [400, "Cannot send to yourself"],
    invalid_contact: [400, "Cannot add yourself as a contact"],
    invalid_body: [400, "Message body is not valid"],
    invalid_client_id: [400, "Client id is not valid"],
    invalid_emoji: [400, "Reaction is not valid"],
    invalid_username: [400, "Username must be 3-24 letters, digits, dot or underscore"],
    invalid_state: [400, "Presence state is not valid"],
    blocked: [403, "This account is not accepting your messages"],
    not_sender: [403, "Only the sender can do that"],
    not_recipient: [403, "Only the recipient can do that"],
    not_participant: [403, "Not your conversation"],
    deleted: [409, "Deleted messages cannot be edited"],
  };
  for (const [key, [status, message]] of Object.entries(known)) {
    if (message.includes(key)) return new ApiError(status, key, message);
  }
  console.error("messenger-api database error", code, message);
  return new ApiError(500, "internal_error", "Internal server error");
}

function profileJson(row: Record<string, unknown>): Record<string, unknown> {
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    createdAt: row.created_at ? new Date(row.created_at as string).getTime() : null,
    status: row.status,
    phone: row.phone,
    email: row.email,
    bio: row.bio,
  };
}

function adminClient(): Client {
  return createClient(SUPABASE_URL, SERVICE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

function anonClient(): Client {
  return createClient(SUPABASE_URL, ANON_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

async function asUser(req: Request): Promise<{ client: Client; token: string; userId: string }> {
  const token = bearer(req);
  if (!token) {
    throw new ApiError(401, "missing_token", "Authorization bearer token is required");
  }
  const client = createClient(SUPABASE_URL, ANON_KEY, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await client.auth.getUser(token);
  if (error || !data.user) {
    throw new ApiError(401, "invalid_token", "Token is invalid or expired");
  }
  return { client, token, userId: data.user.id };
}

async function findProfileById(client: Client, id: string) {
  const { data, error } = await client.from("profiles").select("*").eq("id", id).maybeSingle();
  if (error) throw mapDbError(error);
  return data as Record<string, unknown> | null;
}

async function findProfileByUsername(client: Client, username: string) {
  const { data, error } = await client
    .from("profiles")
    .select("*")
    .eq("username_lower", username.toLowerCase())
    .maybeSingle();
  if (error) throw mapDbError(error);
  return data as Record<string, unknown> | null;
}

function sessionBody(session: Record<string, unknown>, account: Record<string, unknown>) {
  const expiresAt = typeof session.expires_at === "number"
    ? session.expires_at * 1000
    : Date.now() + 3_600_000;
  return {
    token: session.access_token,
    refreshToken: session.refresh_token,
    expiresAt,
    account,
  };
}

async function setPresence(client: Client, state: "ONLINE" | "OFFLINE") {
  await client.rpc("mp_set_presence", {
    p_state: state,
    p_availability: "Available",
  });
}

async function register(req: Request): Promise<Response> {
  const body = await readBody(req);
  const username = text(body.username, "username", 3, 24);
  if (!USERNAME.test(username)) {
    throw new ApiError(
      400,
      "invalid_username",
      "Username must be 3-24 letters, digits, dot or underscore",
    );
  }
  const displayName = text(body.displayName, "display_name", 1, 60);
  const password = text(body.password, "password", 8, 200);
  const phone = optionalText(body.phone, "phone", 40);
  const email = optionalText(body.email, "email", 200);

  const admin = adminClient();
  const existing = await findProfileByUsername(admin, username);
  if (existing) {
    throw new ApiError(409, "username_taken", "An account with that username already exists");
  }

  const syntheticEmail = `${username.toLowerCase()}@${EMAIL_DOMAIN}`;
  const created = await admin.auth.admin.createUser({
    email: syntheticEmail,
    password,
    email_confirm: true,
    user_metadata: { username, displayName },
  });
  if (created.error || !created.data.user) {
    if (/already|exists|registered/i.test(created.error?.message ?? "")) {
      throw new ApiError(409, "username_taken", "An account with that username already exists");
    }
    throw new ApiError(400, "invalid_account", created.error?.message ?? "Could not create account");
  }

  const userId = created.data.user.id;
  const profile = await admin.rpc("mp_register_profile", {
    p_id: userId,
    p_username: username,
    p_display_name: displayName,
    p_phone: phone,
    p_email: email,
    p_bio: null,
  });
  if (profile.error) {
    await admin.auth.admin.deleteUser(userId);
    throw mapDbError(profile.error);
  }

  const signedIn = await anonClient().auth.signInWithPassword({
    email: syntheticEmail,
    password,
  });
  if (signedIn.error || !signedIn.data.session) {
    throw new ApiError(500, "session_failed", "Account was created but sign-in failed");
  }

  const userClient = createClient(SUPABASE_URL, ANON_KEY, {
    global: { headers: { Authorization: `Bearer ${signedIn.data.session.access_token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  await setPresence(userClient, "ONLINE").catch(() => undefined);

  return json(201, sessionBody(
    signedIn.data.session as unknown as Record<string, unknown>,
    profile.data as Record<string, unknown>,
  ));
}

async function login(req: Request): Promise<Response> {
  const body = await readBody(req);
  const username = text(body.username, "username", 3, 24);
  const password = text(body.password, "password", 1, 200);

  const admin = adminClient();
  const profile = await findProfileByUsername(admin, username);
  if (!profile) {
    throw new ApiError(401, "invalid_credentials", "Invalid username or password");
  }

  const signedIn = await anonClient().auth.signInWithPassword({
    email: `${username.toLowerCase()}@${EMAIL_DOMAIN}`,
    password,
  });
  if (signedIn.error || !signedIn.data.session) {
    throw new ApiError(401, "invalid_credentials", "Invalid username or password");
  }

  const userClient = createClient(SUPABASE_URL, ANON_KEY, {
    global: { headers: { Authorization: `Bearer ${signedIn.data.session.access_token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  await setPresence(userClient, "ONLINE").catch(() => undefined);

  return json(200, sessionBody(
    signedIn.data.session as unknown as Record<string, unknown>,
    profileJson(profile),
  ));
}

async function refresh(req: Request): Promise<Response> {
  const body = await readBody(req);
  const refreshToken = text(body.refreshToken, "refresh_token", 1, 4096);
  const refreshed = await anonClient().auth.refreshSession({ refresh_token: refreshToken });
  if (refreshed.error || !refreshed.data.session || !refreshed.data.user) {
    throw new ApiError(401, "invalid_refresh_token", "Refresh token is invalid or expired");
  }
  const profile = await findProfileById(adminClient(), refreshed.data.user.id);
  if (!profile) {
    throw new ApiError(401, "no_account", "Account no longer exists");
  }
  return json(200, sessionBody(
    refreshed.data.session as unknown as Record<string, unknown>,
    profileJson(profile),
  ));
}

async function route(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = apiPath(url.pathname);
  const method = req.method.toUpperCase();

  if (method === "GET" && path === "/healthz") {
    return json(200, { ok: true, service: "messenger-pro-supabase", time: Date.now() });
  }

  if (method === "POST" && path === "/v1/accounts") return await register(req);
  if (method === "POST" && path === "/v1/sessions") return await login(req);
  if (method === "POST" && path === "/v1/sessions/refresh") return await refresh(req);

  const auth = await asUser(req);

  if (method === "DELETE" && path === "/v1/sessions") {
    const admin = adminClient();
    await setPresence(auth.client, "OFFLINE").catch(() => undefined);
    await admin.auth.admin.signOut(auth.token, "local").catch(() => undefined);
    return json(200, { ok: true });
  }

  if (method === "GET" && path === "/v1/me") {
    const profile = await findProfileById(auth.client, auth.userId);
    if (!profile) throw new ApiError(401, "no_account", "Account no longer exists");
    return json(200, { account: profileJson(profile) });
  }

  if (method === "GET" && path === "/v1/accounts") {
    const username = url.searchParams.get("username") ?? "";
    if (!username) throw new ApiError(404, "no_such_account", "No account with that username");
    const profile = await findProfileByUsername(auth.client, username);
    if (!profile) throw new ApiError(404, "no_such_account", "No account with that username");
    return json(200, { account: profileJson(profile) });
  }

  const accountMatch = /^\/v1\/accounts\/([^/]+)$/.exec(path);
  if (accountMatch && method === "GET") {
    const id = uuidParam(accountMatch[1], "account_id");
    const profile = await findProfileById(auth.client, id);
    if (!profile) throw new ApiError(404, "no_such_account", "No account with that id");
    return json(200, { account: profileJson(profile) });
  }

  if (method === "GET" && path === "/v1/contacts") {
    const { data, error } = await auth.client.rpc("mp_list_contacts");
    if (error) throw mapDbError(error);
    return json(200, { contacts: data ?? [] });
  }

  const contactMatch = /^\/v1\/contacts\/([^/]+)$/.exec(path);
  if (contactMatch && method === "PUT") {
    const id = uuidParam(contactMatch[1], "contact_id");
    const body = await readBody(req);
    const { data, error } = await auth.client.rpc("mp_upsert_contact", {
      p_contact: id,
      p_nickname: optionalText(body.nickname, "nickname", 100),
      p_note: optionalText(body.note, "note", 2000),
      p_favourite: optionalBool(body.favourite, "favourite"),
      p_blocked: optionalBool(body.blocked, "blocked"),
      p_hidden: optionalBool(body.hidden, "hidden"),
      p_ringtone: optionalText(body.ringtone, "ringtone", 200),
    });
    if (error) throw mapDbError(error);
    return json(200, { contact: data });
  }
  if (contactMatch && method === "DELETE") {
    const id = uuidParam(contactMatch[1], "contact_id");
    const { error } = await auth.client.rpc("mp_delete_contact", { p_contact: id });
    if (error) throw mapDbError(error);
    return json(200, { ok: true });
  }

  if (method === "POST" && path === "/v1/messages") {
    const body = await readBody(req);
    const recipient = text(body.to, "recipient", 1, 80);
    const { data, error } = await auth.client.rpc("mp_send_message", {
      p_recipient: uuidParam(recipient, "recipient"),
      p_body: text(body.body, "body", 1, 8000),
      p_client_id: text(body.clientId, "client_id", 1, 100),
      p_reply_to: body.replyToId ? uuidParam(String(body.replyToId), "reply_to") : null,
      p_expires_at: timestamp(body.expiresAt, "expires_at"),
      p_transcript: optionalText(body.transcript, "transcript", 20000),
    });
    if (error) throw mapDbError(error);
    return json(201, { message: data });
  }

  const threadMatch = /^\/v1\/messages\/([^/]+)$/.exec(path);
  if (threadMatch && method === "GET") {
    const peer = uuidParam(threadMatch[1], "peer_id");
    const since = numberParam(url.searchParams.get("since"), 0, 0, Number.MAX_SAFE_INTEGER);
    const limit = numberParam(url.searchParams.get("limit"), 200, 1, 500);
    const { data, error } = await auth.client.rpc("mp_list_messages", {
      p_peer: peer,
      p_since: since,
      p_limit: limit,
    });
    if (error) throw mapDbError(error);
    return json(200, data);
  }

  const messageMatch = /^\/v1\/messages\/([^/]+)$/.exec(path);
  if (messageMatch && method === "PATCH") {
    const id = uuidParam(messageMatch[1], "message_id");
    const body = await readBody(req);
    const { data, error } = await auth.client.rpc("mp_edit_message", {
      p_message: id,
      p_body: text(body.body, "body", 1, 8000),
    });
    if (error) throw mapDbError(error);
    return json(200, { message: data });
  }
  if (messageMatch && method === "DELETE") {
    const id = uuidParam(messageMatch[1], "message_id");
    const { error } = await auth.client.rpc("mp_delete_message", { p_message: id });
    if (error) throw mapDbError(error);
    return json(200, { ok: true });
  }

  const readMatch = /^\/v1\/messages\/([^/]+)\/read$/.exec(path);
  if (readMatch && method === "POST") {
    const id = uuidParam(readMatch[1], "message_id");
    const { data, error } = await auth.client.rpc("mp_mark_read", { p_message: id });
    if (error) throw mapDbError(error);
    return json(200, { message: data });
  }

  const reactionMatch = /^\/v1\/messages\/([^/]+)\/reactions$/.exec(path);
  if (reactionMatch && method === "POST") {
    const id = uuidParam(reactionMatch[1], "message_id");
    const body = await readBody(req);
    const { data, error } = await auth.client.rpc("mp_add_reaction", {
      p_message: id,
      p_emoji: text(body.emoji, "emoji", 1, 16),
    });
    if (error) throw mapDbError(error);
    return json(200, { message: data });
  }
  if (reactionMatch && method === "DELETE") {
    const id = uuidParam(reactionMatch[1], "message_id");
    const { data, error } = await auth.client.rpc("mp_remove_reaction", { p_message: id });
    if (error) throw mapDbError(error);
    return json(200, { message: data });
  }

  if (method === "GET" && path === "/v1/threads") {
    const { data, error } = await auth.client.rpc("mp_list_threads");
    if (error) throw mapDbError(error);
    return json(200, { threads: data ?? [] });
  }

  if (method === "POST" && path === "/v1/presence") {
    const body = await readBody(req);
    const state = body.state === "OFFLINE" ? "OFFLINE" : "ONLINE";
    const availability = typeof body.availability === "string"
      ? body.availability.slice(0, 60)
      : "Available";
    const { data, error } = await auth.client.rpc("mp_set_presence", {
      p_state: state,
      p_availability: availability,
    });
    if (error) throw mapDbError(error);
    return json(200, { presence: data });
  }

  const presenceMatch = /^\/v1\/presence\/([^/]+)$/.exec(path);
  if (presenceMatch && method === "GET") {
    const id = uuidParam(presenceMatch[1], "account_id");
    const { data, error } = await auth.client.rpc("mp_get_presence", { p_account: id });
    if (error) throw mapDbError(error);
    return json(200, { presence: data });
  }

  if (method === "GET" && path === "/v1/sync") {
    const since = numberParam(url.searchParams.get("since"), 0, 0, Number.MAX_SAFE_INTEGER);
    const { data, error } = await auth.client.rpc("mp_sync", { p_since: since });
    if (error) throw mapDbError(error);
    return json(200, data);
  }

  if (method === "GET" && path === "/v1/events") {
    return streamEvents(auth.client, numberParam(url.searchParams.get("since"), 0, 0, Number.MAX_SAFE_INTEGER));
  }

  throw new ApiError(404, "not_found", "No such endpoint");
}

function streamEvents(client: Client, initialCursor: number): Response {
  const encoder = new TextEncoder();
  let cursor = initialCursor;
  let done = false;
  let ticker = 0;
  let heartbeat = 0;
  let closer = 0;

  const stream = new ReadableStream({
    start(controller) {
      const stop = () => {
        if (done) return;
        done = true;
        clearInterval(ticker);
        clearInterval(heartbeat);
        clearTimeout(closer);
        try {
          controller.close();
        } catch {
          // The client may already have disconnected.
        }
      };
      const pump = async () => {
        if (done) return;
        try {
          const { data, error } = await client.rpc("mp_sync", { p_since: cursor, p_limit: 100 });
          if (error) return stop();
          const page = data as { events?: Array<{ seq: number; type: string; payload: unknown }>; cursor?: number };
          for (const event of page.events ?? []) {
            controller.enqueue(encoder.encode(
              `id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event.payload)}\n\n`,
            ));
            cursor = event.seq;
          }
          if (typeof page.cursor === "number") cursor = page.cursor;
        } catch {
          stop();
        }
      };
      void pump();
      ticker = setInterval(() => void pump(), 1000);
      heartbeat = setInterval(() => {
        if (!done) controller.enqueue(encoder.encode(": ping\n\n"));
      }, 15000);
      closer = setTimeout(stop, 25000);
    },
    cancel() {
      done = true;
      clearInterval(ticker);
      clearInterval(heartbeat);
      clearTimeout(closer);
    },
  });

  return new Response(stream, {
    status: 200,
    headers: {
      ...CORS,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive",
    },
  });
}

Deno.serve((req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  return route(req).catch(fail);
});
