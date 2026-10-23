import { createServer } from 'node:http';
import { hashPassword, verifyPassword, signToken, verifyToken, bearer, randomId } from './auth.mjs';
import { ApiError } from './errors.mjs';
import { publicAccount } from './store.mjs';

const MAX_BODY = 1_000_000;
const USERNAME = /^[a-zA-Z0-9._]{3,24}$/;

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization,content-type',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS'
  });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new ApiError(413, 'too_large', 'Request body is too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new ApiError(400, 'invalid_json', 'Body must be valid JSON'));
      }
    });
    req.on('error', reject);
  });
}

function text(value, field, { min = 1, max = 8000 } = {}) {
  if (typeof value !== 'string') throw new ApiError(400, 'invalid_' + field, `${field} must be a string`);
  const trimmed = value.trim();
  if (trimmed.length < min) throw new ApiError(400, 'invalid_' + field, `${field} is too short`);
  if (trimmed.length > max) throw new ApiError(400, 'invalid_' + field, `${field} is too long`);
  return trimmed;
}

export function createApp({ store, secret, logger = console }) {
  const loginAttempts = new Map();

  function rateLimit(key, max, windowMs) {
    const now = Date.now();
    const entry = loginAttempts.get(key) ?? { count: 0, resetAt: now + windowMs };
    if (now > entry.resetAt) {
      entry.count = 0;
      entry.resetAt = now + windowMs;
    }
    entry.count++;
    loginAttempts.set(key, entry);
    if (entry.count > max) throw new ApiError(429, 'rate_limited', 'Too many attempts; try again later');
  }

  function authenticate(req) {
    const token = bearer(req.headers.authorization);
    if (!token) throw new ApiError(401, 'missing_token', 'Authorization bearer token is required');
    const payload = verifyToken(token, secret);
    if (!payload) throw new ApiError(401, 'invalid_token', 'Token is invalid');
    const session = store.findSession(payload.jti);
    if (!session || session.account_id !== payload.sub) {
      throw new ApiError(401, 'revoked_token', 'Session is no longer valid');
    }
    const account = store.findAccountById(payload.sub);
    if (!account) throw new ApiError(401, 'no_account', 'Account no longer exists');
    return { account, payload };
  }

  async function route(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;
    const method = req.method ?? 'GET';

    if (method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'authorization,content-type',
        'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
        'Access-Control-Max-Age': '600'
      });
      return res.end();
    }

    if (method === 'GET' && path === '/healthz') {
      return json(res, 200, { ok: true, service: 'messenger-pro', time: Date.now() });
    }

    if (method === 'POST' && path === '/v1/accounts') {
      const body = await readBody(req);
      const username = text(body.username, 'username', { min: 3, max: 24 });
      if (!USERNAME.test(username)) {
        throw new ApiError(400, 'invalid_username', 'Username must be 3-24 letters, digits, dot or underscore');
      }
      const displayName = text(body.displayName, 'display_name', { max: 60 });
      const password = text(body.password, 'password', { min: 8, max: 200 });
      if (store.findAccountByUsername(username)) {
        throw new ApiError(409, 'username_taken', 'An account with that username already exists');
      }
      const { salt, hash } = hashPassword(password);
      const account = store.createAccount({ username, displayName, passwordHash: hash, salt, phone: body.phone ?? null, email: body.email ?? null });
      const jti = randomId(16);
      store.createSession(account.id, jti);
      store.setPresence(account.id, 'ONLINE');
      return json(res, 201, { token: signToken({ accountId: account.id, jti }, secret), account: publicAccount(account) });
    }

    if (method === 'POST' && path === '/v1/sessions') {
      const body = await readBody(req);
      const username = text(body.username, 'username', { min: 3, max: 24 });
      const password = text(body.password, 'password', { min: 1, max: 200 });
      const ip = req.socket.remoteAddress ?? 'unknown';
      rateLimit(`${ip}:${username.toLowerCase()}`, 8, 60_000);
      const account = store.findAccountByUsername(username);
      if (!account || !verifyPassword(password, account.salt, account.password_hash)) {
        throw new ApiError(401, 'invalid_credentials', 'Invalid username or password');
      }
      const jti = randomId(16);
      store.createSession(account.id, jti);
      store.setPresence(account.id, 'ONLINE');
      return json(res, 200, { token: signToken({ accountId: account.id, jti }, secret), account: publicAccount(account) });
    }

    const auth = authenticate(req);

    if (method === 'DELETE' && path === '/v1/sessions') {
      store.revokeSession(auth.payload.jti);
      store.setPresence(auth.account.id, 'OFFLINE');
      return json(res, 200, { ok: true });
    }

    if (method === 'GET' && path === '/v1/me') {
      return json(res, 200, { account: publicAccount(auth.account) });
    }

    if (method === 'GET' && path === '/v1/accounts') {
      const username = (url.searchParams.get('username') ?? '').toLowerCase();
      const account = username ? store.findAccountByUsername(username) : null;
      if (!account) throw new ApiError(404, 'no_such_account', 'No account with that username');
      return json(res, 200, { account: publicAccount(account) });
    }

    const accountMatch = /^\/v1\/accounts\/([^/]+)$/.exec(path);
    if (accountMatch && method === 'GET') {
      const account = store.findAccountById(accountMatch[1]);
      if (!account) throw new ApiError(404, 'no_such_account', 'No account with that id');
      return json(res, 200, { account: publicAccount(account) });
    }

    if (method === 'GET' && path === '/v1/contacts') {
      return json(res, 200, { contacts: store.listContacts(auth.account.id) });
    }

    const contactMatch = /^\/v1\/contacts\/([^/]+)$/.exec(path);
    if (contactMatch && method === 'PUT') {
      const body = await readBody(req);
      const contact = store.upsertContact(auth.account.id, contactMatch[1], {
        nickname: body.nickname ?? undefined,
        note: body.note ?? undefined,
        favourite: body.favourite ?? undefined,
        blocked: body.blocked ?? undefined,
        hidden: body.hidden ?? undefined,
        ringtone: body.ringtone ?? undefined
      });
      return json(res, 200, {
        contact: {
          id: contact.owner_id + '->' + contact.contact_id,
          ownerId: contact.owner_id,
          contactAccountId: contact.contact_id,
          nickname: contact.nickname,
          note: contact.note,
          favourite: Boolean(contact.favourite),
          blocked: Boolean(contact.blocked),
          hidden: Boolean(contact.hidden),
          ringtone: contact.ringtone
        }
      });
    }
    if (contactMatch && method === 'DELETE') {
      store.deleteContact(auth.account.id, contactMatch[1]);
      return json(res, 200, { ok: true });
    }

    if (method === 'POST' && path === '/v1/messages') {
      const body = await readBody(req);
      const recipientId = text(body.to, 'recipient', { max: 80 });
      const messageBody = text(body.body, 'body', { min: 1, max: 8000 });
      const clientId = text(body.clientId, 'client_id', { min: 1, max: 100 });
      const message = store.sendMessage({
        senderId: auth.account.id,
        recipientId,
        body: messageBody,
        clientId,
        replyToId: body.replyToId ?? null,
        expiresAt: body.expiresAt ?? null,
        transcript: body.transcript ?? null
      });
      return json(res, 201, { message });
    }

    const threadMatch = /^\/v1\/messages\/([^/]+)$/.exec(path);
    if (threadMatch && method === 'GET') {
      const since = Number(url.searchParams.get('since') ?? 0);
      const limit = Math.min(Number(url.searchParams.get('limit') ?? 200), 500);
      const peerId = threadMatch[1];
      const raw = store.db.prepare(`
        SELECT * FROM messages
        WHERE seq > ? AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
          AND (expires_at IS NULL OR expires_at > ?)
        ORDER BY seq ASC LIMIT ?
      `).all(since, auth.account.id, peerId, peerId, auth.account.id, store.now(), limit);
      store.markDelivered(auth.account.id, raw);
      const messages = store.listMessages(auth.account.id, peerId, { sinceSeq: since, limit });
      const cursor = messages.length ? messages[messages.length - 1].seq : since;
      return json(res, 200, { messages, cursor });
    }

    const messageMatch = /^\/v1\/messages\/([^/]+)$/.exec(path);
    if (messageMatch && method === 'PATCH') {
      const body = await readBody(req);
      const message = store.editMessage(auth.account.id, messageMatch[1], text(body.body, 'body', { min: 1, max: 8000 }));
      return json(res, 200, { message });
    }
    if (messageMatch && method === 'DELETE') {
      store.deleteMessage(auth.account.id, messageMatch[1], url.searchParams.get('forEveryone') === '1');
      return json(res, 200, { ok: true });
    }

    const readMatch = /^\/v1\/messages\/([^/]+)\/read$/.exec(path);
    if (readMatch && method === 'POST') {
      return json(res, 200, { message: store.markRead(auth.account.id, readMatch[1]) });
    }

    const reactionMatch = /^\/v1\/messages\/([^/]+)\/reactions$/.exec(path);
    if (reactionMatch && method === 'POST') {
      const body = await readBody(req);
      const emoji = text(body.emoji, 'emoji', { min: 1, max: 16 });
      return json(res, 200, { message: store.addReaction(auth.account.id, reactionMatch[1], emoji) });
    }
    if (reactionMatch && method === 'DELETE') {
      return json(res, 200, { message: store.removeReaction(auth.account.id, reactionMatch[1]) });
    }

    if (method === 'GET' && path === '/v1/threads') {
      return json(res, 200, { threads: store.listThreads(auth.account.id) });
    }

    if (method === 'POST' && path === '/v1/presence') {
      const body = await readBody(req);
      const state = body.state === 'OFFLINE' ? 'OFFLINE' : 'ONLINE';
      const availability = typeof body.availability === 'string' ? body.availability.slice(0, 60) : 'Available';
      return json(res, 200, { presence: store.setPresence(auth.account.id, state, availability) });
    }

    const presenceMatch = /^\/v1\/presence\/([^/]+)$/.exec(path);
    if (presenceMatch && method === 'GET') {
      return json(res, 200, { presence: store.getPresence(presenceMatch[1]) });
    }

    if (method === 'GET' && path === '/v1/sync') {
      const since = Number(url.searchParams.get('since') ?? 0);
      return json(res, 200, store.eventsSince(auth.account.id, since));
    }

    if (method === 'GET' && path === '/v1/events') {
      return streamEvents(req, res, url, auth.account.id);
    }

    throw new ApiError(404, 'not_found', 'No such endpoint');
  }

  function streamEvents(req, res, url, accountId) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'Access-Control-Allow-Origin': '*'
    });
    let cursor = Number(url.searchParams.get('since') ?? 0);
    let closed = false;
    const send = () => {
      if (closed) return;
      const page = store.eventsSince(accountId, cursor, 100);
      for (const event of page.events) {
        res.write(`id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event.payload)}\n\n`);
      }
      cursor = page.cursor;
    };
    send();
    const ticker = setInterval(send, 1000);
    const heartbeat = setInterval(() => { if (!closed) res.write(': ping\n\n'); }, 15000);
    req.on('close', () => {
      closed = true;
      clearInterval(ticker);
      clearInterval(heartbeat);
    });
  }

  const server = createServer((req, res) => {
    route(req, res).catch(error => {
      if (res.headersSent) {
        res.end();
        return;
      }
      if (error instanceof ApiError) {
        return json(res, error.status, { error: { code: error.code, message: error.message } });
      }
      logger.error('unhandled_error', error);
      json(res, 500, { error: { code: 'internal_error', message: 'Internal server error' } });
    });
  });

  return { server, store };
}
